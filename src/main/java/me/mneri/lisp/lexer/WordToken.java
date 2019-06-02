package me.mneri.lisp.lexer;

public class WordToken extends Token {
    private String word;

    public WordToken(String word) {
        this.word = word;
    }

    @Override
    public String toString() {
        return "<" + word + ">";
    }
}
