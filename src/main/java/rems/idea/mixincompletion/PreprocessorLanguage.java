package rems.idea.mixincompletion;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Shared model of the ReplayMod preprocessor syntax used by editor features. */
final class PreprocessorLanguage {
    private static final List<Directive> LEGACY_DIRECTIVES = List.of(
            new Directive("if", "Start a conditional block", Argument.EXPRESSION),
            new Directive("ifdef", "Start a block when a variable is defined", Argument.VARIABLE),
            new Directive("elseif", "Add another conditional branch", Argument.EXPRESSION),
            new Directive("else", "Add a fallback branch", Argument.NONE),
            new Directive("endif", "End a conditional block", Argument.NONE),
            new Directive("disable-remap", "Disable remapping for following source", Argument.NONE),
            new Directive("enable-remap", "Enable remapping again", Argument.NONE));
    private static final List<Directive> LIUYUE_DIRECTIVES = List.of(
            new Directive("if", "Start a conditional block", Argument.EXPRESSION),
            new Directive("ifdef", "Start a block when a variable is defined", Argument.VARIABLE),
            new Directive("ifndef", "Start a block when a variable is not defined", Argument.VARIABLE),
            new Directive("elseif", "Add another conditional branch", Argument.EXPRESSION),
            new Directive("elif", "Alias for elseif", Argument.EXPRESSION),
            new Directive("else", "Add a fallback branch", Argument.NONE),
            new Directive("endif", "End a conditional block", Argument.NONE),
            new Directive("define", "Define a reusable condition alias", Argument.DEFINITION),
            new Directive("error", "Fail preprocessing when this line is active", Argument.MESSAGE),
            new Directive("warn", "Print a warning when this line is active", Argument.MESSAGE),
            new Directive("replace", "Replace the preceding code when a condition matches", Argument.REPLACEMENT),
            new Directive("case", "Start a first-matching case group", Argument.OPTIONAL),
            new Directive("endcase", "End a case group", Argument.NONE),
            new Directive("disable-remap", "Disable remapping for following source", Argument.NONE),
            new Directive("enable-remap", "Enable remapping again", Argument.NONE));
    static final List<Directive> DIRECTIVES = java.util.stream.Stream.concat(
            LIUYUE_DIRECTIVES.stream(),
            java.util.stream.Stream.of(new Directive(
                    "import", "Add an import from an active conditional branch", Argument.IMPORT)))
            .toList();

    private static final Pattern DIRECTIVE = Pattern.compile(
            "^\\s*(?://\\$\\$\\s*)*//#([A-Za-z-]+)(?:\\s+(.*?))?\\s*$");
    private static final Pattern DEFINE = Pattern.compile("^\\s*//#define\\s+([A-Za-z_$][A-Za-z0-9_$]*)\\s+(.+?)\\s*$");
    private static final Pattern VERSION_MODULE = Pattern.compile("([0-9]+(?:\\.[0-9]+){1,2})\\.main$");
    private static final Pattern DEFINED = Pattern.compile("defined\\s*\\(\\s*([A-Za-z_$][A-Za-z0-9_$]*)\\s*\\)");
    private static final Pattern BARE_COMPARISON = Pattern.compile("(?:>=|<=|==|!=|>|<)\\s*[0-9][\\w.]*");
    private static final Pattern BARE_VERSION = Pattern.compile("[0-9][\\w.]*");
    private static final Pattern BARE_RANGE = Pattern.compile("[0-9][\\w.]*\\.\\.[0-9][\\w.]*");
    private static final int MAX_DEFINE_DEPTH = 8;

    private PreprocessorLanguage() {}

    static Directive directive(String name) {
        for (Directive directive : DIRECTIVES) {
            if (directive.name().equals(name)) return directive;
        }
        return null;
    }

    static List<Directive> completionDirectives(PsiFile file) {
        return usesLiuyuePreprocessor(file) ? LIUYUE_DIRECTIVES : LEGACY_DIRECTIVES;
    }

    private static boolean usesLiuyuePreprocessor(PsiFile file) {
        VirtualFile directory = file.getVirtualFile() == null
                ? null : file.getVirtualFile().getParent();
        while (directory != null) {
            for (String name : List.of("settings.gradle", "settings.gradle.kts",
                    "build.gradle", "build.gradle.kts", "gradle.properties")) {
                VirtualFile configuration = directory.findChild(name);
                if (configuration == null || configuration.isDirectory()) continue;
                try {
                    if (VfsUtilCore.loadText(configuration).toLowerCase(java.util.Locale.ROOT)
                            .contains("liuyuexiaoyu1")) {
                        return true;
                    }
                } catch (IOException ignored) {
                    // Continue towards the project root and fall back to the legacy dialect.
                }
            }
            directory = directory.getParent();
        }
        return false;
    }

    static ParsedDirective parseDirective(String line) {
        Matcher matcher = DIRECTIVE.matcher(line);
        if (!matcher.matches()) return null;
        return new ParsedDirective(matcher.group(1), matcher.group(2) == null ? "" : matcher.group(2).strip());
    }

    static boolean opensConditional(String line) {
        ParsedDirective directive = parseDirective(line);
        return directive != null && (directive.name().equals("if")
                || directive.name().equals("ifdef") || directive.name().equals("ifndef"));
    }

    static boolean closesConditional(String line) {
        ParsedDirective directive = parseDirective(line);
        return directive != null && directive.name().equals("endif");
    }

    static boolean hasEnclosingConditional(Document document, int currentLine) {
        int depth = 0;
        for (int line = Math.min(currentLine - 1, document.getLineCount() - 1); line >= 0; line--) {
            String text = lineText(document, line);
            if (closesConditional(text)) depth++;
            else if (opensConditional(text)) {
                if (depth == 0) return true;
                depth--;
            }
        }
        return false;
    }

    static int conditionalDepthBeforeLine(Document document, int currentLine) {
        int depth = 0;
        int end = Math.min(currentLine, document.getLineCount());
        for (int line = 0; line < end; line++) {
            String text = lineText(document, line);
            if (opensConditional(text)) depth++;
            else if (closesConditional(text)) depth = Math.max(0, depth - 1);
        }
        return depth;
    }

    static String codePrefixBeforeLine(Document document, int currentLine) {
        Deque<String> prefixes = new ArrayDeque<>();
        int end = Math.min(currentLine, document.getLineCount());
        for (int line = 0; line < end; line++) {
            String text = lineText(document, line).stripLeading();
            ParsedDirective directive = parseDirective(text);
            if (directive == null) continue;
            if (directive.name().equals("if") || directive.name().equals("ifdef")
                    || directive.name().equals("ifndef")) {
                int directiveStart = text.lastIndexOf("//#");
                String parentPrefix = directiveStart < 0 ? "" : text.substring(0, directiveStart);
                prefixes.push(parentPrefix + "//$$ ");
            } else if (directive.name().equals("endif") && !prefixes.isEmpty()) {
                prefixes.pop();
            }
        }
        return prefixes.isEmpty() ? "" : prefixes.peek();
    }

    static boolean hasVersionContext(Document document, int currentLine) {
        if (hasEnclosingConditional(document, currentLine)) return true;
        if (currentLine < 0 || currentLine >= document.getLineCount()) return false;
        String line = lineText(document, currentLine);
        return line.contains("//?") || line.contains("/*?") || line.contains("//#replace");
    }

    static boolean hasClosingConditional(Document document, int currentLine, int requiredDepth) {
        int depth = requiredDepth;
        for (int line = currentLine; line < document.getLineCount(); line++) {
            String text = lineText(document, line);
            if (opensConditional(text)) depth++;
            else if (closesConditional(text) && --depth == 0) return true;
        }
        return false;
    }

    /** Returns whether the requested source line is active for the supplied variables. */
    static boolean isLineActive(Document document, int targetLine, Map<String, Integer> variables) {
        List<String> lines = new ArrayList<>(document.getLineCount());
        for (int line = 0; line < document.getLineCount(); line++) lines.add(lineText(document, line));
        return isLineActive(lines, targetLine, variables);
    }

    static boolean isLineActive(List<String> lines, int targetLine, Map<String, Integer> variables) {
        Map<String, String> definitions = collectDefinitions(lines);
        Deque<Branch> stack = new ArrayDeque<>();
        boolean active = true;
        boolean inCase = false;
        boolean caseMatched = false;
        int end = Math.min(targetLine, lines.size() - 1);
        for (int index = 0; index <= end; index++) {
            ParsedDirective directive = parseDirective(lines.get(index));
            if (directive == null) {
                String replacementCondition = replacementCondition(lines.get(index));
                if (replacementCondition != null && index == targetLine) {
                    try {
                        return active && evaluate(replacementCondition, variables, definitions);
                    } catch (IllegalArgumentException ignored) {
                        return false;
                    }
                }
                CaseBranch caseBranch = parseCaseBranch(lines.get(index));
                if (caseBranch != null) {
                    boolean matches = false;
                    if (!inCase || !caseMatched) {
                        try {
                            matches = caseBranch.fallback()
                                    || evaluate(caseBranch.condition(), variables, definitions);
                        } catch (IllegalArgumentException ignored) {
                            matches = false;
                        }
                    }
                    boolean lineActive = active && matches && (!inCase || !caseMatched);
                    if (inCase && lineActive) caseMatched = true;
                    if (index == targetLine) return lineActive;
                }
                continue;
            }
            String name = directive.name();
            if (name.equals("replace") && index == targetLine) {
                String condition = replacementCondition(lines.get(index));
                try {
                    return condition != null && active && evaluate(condition, variables, definitions);
                } catch (IllegalArgumentException ignored) {
                    return false;
                }
            } else if (name.equals("case")) {
                inCase = true;
                caseMatched = false;
            } else if (name.equals("endcase")) {
                inCase = false;
                caseMatched = false;
            } else if (name.equals("if") || name.equals("ifdef") || name.equals("ifndef")) {
                boolean result;
                try {
                    result = switch (name) {
                        case "ifdef" -> variables.containsKey(directive.argument());
                        case "ifndef" -> !variables.containsKey(directive.argument());
                        default -> evaluate(directive.argument(), variables, definitions);
                    };
                } catch (IllegalArgumentException ignored) {
                    result = false;
                }
                stack.push(new Branch(result, result, false));
                active = allActive(stack);
            } else if (name.equals("elseif") || name.equals("elif")) {
                if (stack.isEmpty()) continue;
                Branch previous = stack.pop();
                boolean result = false;
                if (!previous.matched()) {
                    try {
                        result = evaluate(directive.argument(), variables, definitions);
                    } catch (IllegalArgumentException ignored) {
                        result = false;
                    }
                }
                stack.push(new Branch(result, previous.matched() || result, previous.elseSeen()));
                active = allActive(stack);
            } else if (name.equals("else")) {
                if (stack.isEmpty()) continue;
                Branch previous = stack.pop();
                stack.push(new Branch(!previous.matched(), previous.matched(), true));
                active = allActive(stack);
            } else if (name.equals("endif")) {
                if (!stack.isEmpty()) stack.pop();
                active = allActive(stack);
            }
        }
        return active;
    }

    private static CaseBranch parseCaseBranch(String line) {
        String trimmed = line.strip();
        int marker = trimmed.startsWith("//?") ? 0 : trimmed.lastIndexOf("//?");
        if (marker < 0) return null;
        String directive = trimmed.substring(marker + 3).strip();
        if (directive.equals("else") || directive.startsWith("else ")) {
            return new CaseBranch("1", true);
        }
        int separator = directive.indexOf('?');
        String condition = separator < 0 ? directive : directive.substring(0, separator).strip();
        return condition.isEmpty() ? null : new CaseBranch(condition, false);
    }

    private static String replacementCondition(String line) {
        int marker = line.indexOf("//#replace");
        if (marker < 0) return null;
        String directive = line.substring(marker + "//#replace".length()).strip();
        int separator = directive.indexOf('?');
        if (separator < 0) return null;
        String condition = directive.substring(0, separator).strip();
        return condition.isEmpty() ? null : condition;
    }

    static boolean isValidExpression(String expression) {
        try {
            Map<String, Integer> variables = new LinkedHashMap<>();
            variables.put("MC", 12104);
            Matcher names = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*").matcher(expression);
            while (names.find()) {
                String name = names.group();
                if (!name.equals("in") && !name.equals("not") && !name.equals("defined")) {
                    variables.putIfAbsent(name, 1);
                }
            }
            evaluate(expression, variables, Map.of());
            return true;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    static boolean evaluate(String expression, Map<String, Integer> variables,
                            Map<String, String> definitions) {
        if (expression == null || expression.isBlank()) throw new IllegalArgumentException("Empty expression");
        String expanded = expandShorthand(expression.strip(), variables);
        expanded = expandDefined(expanded, variables, definitions);
        expanded = expandDefinitions(expanded, definitions);
        return new ExpressionParser(expanded, variables).parse();
    }

    static int versionCode(String value) {
        String cleaned = value.replace("_", "");
        if (!cleaned.contains(".")) {
            try {
                return Integer.parseInt(cleaned);
            } catch (NumberFormatException ignored) {
                return -1;
            }
        }
        String[] parts = cleaned.split("\\.");
        if (parts.length < 2 || parts.length > 3) return -1;
        try {
            int major = Integer.parseInt(parts[0]);
            int minor = Integer.parseInt(parts[1]);
            int patch = parts.length > 2 ? Integer.parseInt(parts[2]) : 0;
            return major * 10_000 + minor * 100 + patch;
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    static int moduleVersionCode(String moduleName) {
        Matcher matcher = VERSION_MODULE.matcher(moduleName);
        return matcher.find() ? versionCode(matcher.group(1)) : -1;
    }

    private static Map<String, String> collectDefinitions(List<String> lines) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String line : lines) {
            Matcher matcher = DEFINE.matcher(line);
            if (matcher.matches()) result.putIfAbsent(matcher.group(1), matcher.group(2));
        }
        return result;
    }

    private static boolean isDefined(String name, Map<String, Integer> variables,
                                     Map<String, String> definitions) {
        return variables.containsKey(name.strip()) || definitions.containsKey(name.strip());
    }

    private static String expandShorthand(String text, Map<String, Integer> variables) {
        if (text.isEmpty() || Character.isLetter(text.charAt(0)) || text.charAt(0) == '_'
                || text.charAt(0) == '!' || text.charAt(0) == '(') return text;
        String primary = variables.containsKey("MC") ? "MC"
                : variables.size() == 1 ? variables.keySet().iterator().next() : null;
        if (primary == null) return text;
        if (BARE_RANGE.matcher(text).matches()) return primary + " in " + text;
        if (BARE_COMPARISON.matcher(text).matches()) return primary + " " + text;
        if (BARE_VERSION.matcher(text).matches() && text.contains(".")) return primary + " == " + text;
        return text;
    }

    private static String expandDefined(String text, Map<String, Integer> variables,
                                        Map<String, String> definitions) {
        Matcher matcher = DEFINED.matcher(text);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(result, isDefined(matcher.group(1), variables, definitions) ? "1" : "0");
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static String expandDefinitions(String text, Map<String, String> definitions) {
        String result = text;
        for (int pass = 0; pass < MAX_DEFINE_DEPTH; pass++) {
            StringBuilder expanded = new StringBuilder();
            boolean changed = false;
            for (int index = 0; index < result.length();) {
                char value = result.charAt(index);
                if (Character.isLetter(value) || value == '_') {
                    int end = index + 1;
                    while (end < result.length() && (Character.isLetterOrDigit(result.charAt(end))
                            || result.charAt(end) == '_')) end++;
                    String word = result.substring(index, end);
                    String replacement = definitions.get(word);
                    if (replacement == null) expanded.append(word);
                    else {
                        expanded.append('(').append(replacement).append(')');
                        changed = true;
                    }
                    index = end;
                } else {
                    expanded.append(value);
                    index++;
                }
            }
            result = expanded.toString();
            if (!changed) return result;
        }
        throw new IllegalArgumentException("Cyclic definition");
    }

    private static boolean allActive(Deque<Branch> stack) {
        for (Branch branch : stack) if (!branch.active()) return false;
        return true;
    }

    private static String lineText(Document document, int line) {
        return document.getText().substring(document.getLineStartOffset(line), document.getLineEndOffset(line));
    }

    enum Argument { NONE, EXPRESSION, VARIABLE, DEFINITION, MESSAGE, REPLACEMENT, OPTIONAL, IMPORT }
    record Directive(String name, String description, Argument argument) {}
    record ParsedDirective(String name, String argument) {}
    private record Branch(boolean active, boolean matched, boolean elseSeen) {}
    private record CaseBranch(String condition, boolean fallback) {}

    private static final class ExpressionParser {
        private final String source;
        private final Map<String, Integer> variables;
        private int position;

        private ExpressionParser(String source, Map<String, Integer> variables) {
            this.source = source;
            this.variables = variables;
        }

        private boolean parse() {
            boolean result = parseOr(true);
            whitespace();
            if (position != source.length()) throw new IllegalArgumentException(source);
            return result;
        }

        private boolean parseOr(boolean evaluate) {
            boolean left = parseAnd(evaluate);
            while (true) {
                whitespace();
                if (!source.startsWith("||", position)) return left;
                position += 2;
                boolean right = parseAnd(evaluate && !left);
                left = evaluate && (left || right);
            }
        }

        private boolean parseAnd(boolean evaluate) {
            boolean left = parseUnary(evaluate);
            while (true) {
                whitespace();
                if (!source.startsWith("&&", position)) return left;
                position += 2;
                boolean right = parseUnary(evaluate && left);
                left = evaluate && (left && right);
            }
        }

        private boolean parseUnary(boolean evaluate) {
            whitespace();
            if (position >= source.length()) throw new IllegalArgumentException(source);
            if (source.charAt(position) == '!') {
                position++;
                boolean value = parseUnary(evaluate);
                return evaluate && !value;
            }
            if (source.charAt(position) == '(') {
                position++;
                boolean value = parseOr(evaluate);
                whitespace();
                if (position >= source.length() || source.charAt(position++) != ')') {
                    throw new IllegalArgumentException(source);
                }
                return value;
            }
            int start = position;
            int bracketDepth = 0;
            while (position < source.length()) {
                char value = source.charAt(position);
                if (value == '[') bracketDepth++;
                else if (value == ']') bracketDepth--;
                if (bracketDepth == 0 && (value == ')' || source.startsWith("&&", position)
                        || source.startsWith("||", position))) break;
                position++;
            }
            String atom = source.substring(start, position).strip();
            if (atom.isEmpty()) throw new IllegalArgumentException(source);
            return evaluate && evaluateAtom(atom);
        }

        private boolean evaluateAtom(String atom) {
            Matcher set = Pattern.compile("(.+?)\\s+(not\\s+)?in\\s+\\[(.+)]").matcher(atom);
            if (set.matches()) {
                int left = value(set.group(1));
                boolean inside = false;
                for (String item : set.group(3).split(",")) inside |= value(item) == left;
                return set.group(2) == null ? inside : !inside;
            }
            Matcher range = Pattern.compile("(.+?)\\s+(not\\s+)?in\\s+(.+?)\\.\\.(.+)").matcher(atom);
            if (range.matches()) {
                int left = value(range.group(1));
                boolean inside = left >= value(range.group(3)) && left < value(range.group(4));
                return range.group(2) == null ? inside : !inside;
            }
            Matcher comparison = Pattern.compile("(.+?)(==|!=|<=|>=|<|>)(.+)").matcher(atom);
            if (comparison.matches()) {
                int left = value(comparison.group(1));
                int right = value(comparison.group(3));
                return switch (comparison.group(2)) {
                    case "==" -> left == right;
                    case "!=" -> left != right;
                    case "<=" -> left <= right;
                    case ">=" -> left >= right;
                    case "<" -> left < right;
                    case ">" -> left > right;
                    default -> false;
                };
            }
            return value(atom) != 0;
        }

        private int value(String token) {
            String cleaned = token.strip();
            Integer variable = variables.get(cleaned);
            if (variable != null) return variable;
            int number = versionCode(cleaned);
            if (number >= 0) return number;
            throw new IllegalArgumentException("Unknown value " + cleaned);
        }

        private void whitespace() {
            while (position < source.length() && Character.isWhitespace(source.charAt(position))) position++;
        }
    }
}
