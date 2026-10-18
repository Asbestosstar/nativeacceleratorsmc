package com.asbestosstar.nativeaccelerator.renderer.universal.shader.legacy.isl;

public record IslToken(Kind kind, String lexeme, int line, int column) {
    public enum Kind {
        IDENTIFIER, NUMBER, STRING,
        LEFT_PAREN, RIGHT_PAREN, LEFT_BRACE, RIGHT_BRACE,
        COMMA, SEMICOLON, EQUAL, PLUS, MINUS, STAR, SLASH,
        EOF
    }
}
