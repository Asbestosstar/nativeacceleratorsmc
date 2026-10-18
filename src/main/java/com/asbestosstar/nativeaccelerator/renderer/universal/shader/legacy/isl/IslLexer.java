package com.asbestosstar.nativeaccelerator.renderer.universal.shader.legacy.isl;

import java.util.ArrayList;
import java.util.List;

/** Small clean-room lexer foundation; unsupported language constructs remain explicit diagnostics. */
public final class IslLexer {
    public record Result(List<IslToken> tokens, List<IslDiagnostic> diagnostics) {
        public Result {
            tokens = List.copyOf(tokens);
            diagnostics = List.copyOf(diagnostics);
        }
    }

    public Result lex(String source) {
        String text = source == null ? "" : source;
        List<IslToken> tokens = new ArrayList<>();
        List<IslDiagnostic> diagnostics = new ArrayList<>();
        int index = 0;
        int line = 1;
        int column = 1;
        while (index < text.length()) {
            char c = text.charAt(index);
            if (c == ' ' || c == '\t' || c == '\r') {
                index++;
                column++;
                continue;
            }
            if (c == '\n') {
                index++;
                line++;
                column = 1;
                continue;
            }
            if (c == '/' && index + 1 < text.length() && text.charAt(index + 1) == '/') {
                index += 2;
                column += 2;
                while (index < text.length() && text.charAt(index) != '\n') {
                    index++;
                    column++;
                }
                continue;
            }
            int tokenLine = line;
            int tokenColumn = column;
            IslToken.Kind punctuation = punctuation(c);
            if (punctuation != null) {
                tokens.add(new IslToken(punctuation, String.valueOf(c), tokenLine, tokenColumn));
                index++;
                column++;
                continue;
            }
            if (c == '"') {
                int start = index++;
                column++;
                StringBuilder value = new StringBuilder();
                boolean closed = false;
                while (index < text.length()) {
                    char current = text.charAt(index++);
                    column++;
                    if (current == '"') {
                        closed = true;
                        break;
                    }
                    if (current == '\n') {
                        line++;
                        column = 1;
                    }
                    value.append(current);
                }
                if (!closed) {
                    diagnostics.add(new IslDiagnostic(IslDiagnostic.Severity.ERROR,
                            "unterminated string literal", tokenLine, tokenColumn));
                }
                tokens.add(new IslToken(IslToken.Kind.STRING, text.substring(start, index), tokenLine, tokenColumn));
                continue;
            }
            if (Character.isLetter(c) || c == '_') {
                int start = index;
                do {
                    index++;
                    column++;
                } while (index < text.length()
                        && (Character.isLetterOrDigit(text.charAt(index)) || text.charAt(index) == '_'));
                tokens.add(new IslToken(IslToken.Kind.IDENTIFIER, text.substring(start, index), tokenLine, tokenColumn));
                continue;
            }
            if (Character.isDigit(c) || c == '.') {
                int start = index;
                boolean dot = false;
                while (index < text.length()) {
                    char current = text.charAt(index);
                    if (Character.isDigit(current)) {
                        index++;
                        column++;
                    } else if (current == '.' && !dot) {
                        dot = true;
                        index++;
                        column++;
                    } else {
                        break;
                    }
                }
                tokens.add(new IslToken(IslToken.Kind.NUMBER, text.substring(start, index), tokenLine, tokenColumn));
                continue;
            }
            diagnostics.add(new IslDiagnostic(IslDiagnostic.Severity.ERROR,
                    "unsupported character in ISL source: " + c, tokenLine, tokenColumn));
            index++;
            column++;
        }
        tokens.add(new IslToken(IslToken.Kind.EOF, "", line, column));
        return new Result(tokens, diagnostics);
    }

    private static IslToken.Kind punctuation(char c) {
        return switch (c) {
            case '(' -> IslToken.Kind.LEFT_PAREN;
            case ')' -> IslToken.Kind.RIGHT_PAREN;
            case '{' -> IslToken.Kind.LEFT_BRACE;
            case '}' -> IslToken.Kind.RIGHT_BRACE;
            case ',' -> IslToken.Kind.COMMA;
            case ';' -> IslToken.Kind.SEMICOLON;
            case '=' -> IslToken.Kind.EQUAL;
            case '+' -> IslToken.Kind.PLUS;
            case '-' -> IslToken.Kind.MINUS;
            case '*' -> IslToken.Kind.STAR;
            case '/' -> IslToken.Kind.SLASH;
            default -> null;
        };
    }
}
