package me.mneri.lisp.lexer;

public class NumberToken extends Token {
    private int number;

    public NumberToken(int number) {
        this.number = number;
    }

    @Override
    public String toString() {
        return "<" + number + ">";
    }
}
