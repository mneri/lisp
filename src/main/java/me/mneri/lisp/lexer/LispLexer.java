/*
 * Copyright 2018 Massimo Neri <hello@mneri.me>
 *
 * This file is part of mneri/lisp.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.mneri.lisp.lexer;

import java.io.*;
import java.nio.charset.Charset;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static java.util.Spliterator.*;
import static java.util.Spliterators.spliteratorUnknownSize;

/**
 * Read tokens from text streams.
 *
 * @author Massimo Neri &lt;<a href="mailto:hello@mneri.me">hello@mneri.me</a>&gt;
 */
public class LispLexer implements Closeable {
    //@formatter:off
    private static final int SOT =  0;
    private static final int SYM =  6;
    private static final int NUM = 12;
    private static final int EOT = 18;
    private static final int ERR = 24;

    private static final int ACCM = 1 << 16;
    private static final int MOVE = 1 << 17;
    private static final int LPAR = 1 << 18;
    private static final int RPAR = 1 << 19;
    private static final int WORD = 1 << 20;
    private static final int NMBR = 1 << 21;

    private static final int STATE_MASK = 0x0000ffff;
    //@formatter:on

    //@formatter:off
    private static final int[] ACTIONS = {
    // *                  [0-9]              (                  )                  [:space:]          EOF
       SYM | ACCM | MOVE, NUM | ACCM | MOVE, EOT | LPAR | MOVE, EOT | RPAR | MOVE, SOT | MOVE       , EOT              ,  // SOT
       SYM | ACCM | MOVE, SYM | ACCM | MOVE, EOT | WORD       , EOT | WORD       , EOT | WORD       , EOT | WORD       ,  // SYM
       ERR              , NUM | ACCM | MOVE, EOT | NMBR       , EOT | NMBR       , EOT | NMBR       , EOT | NMBR       }; // NUM
    //@formatter:on

    //@formatter:off
    private static final int ELEMENT_NOT_READ = 0;
    private static final int ELEMENT_READ     = 1;
    private static final int NO_SUCH_ELEMENT  = 2;
    private static final int CLOSED           = 3;
    //@formatter:on

    private static final int DEFAULT_BUFFER_SIZE = 8192;
    private static final int MAX_WORD_SIZE = 32;


    private final char[] buffer;
    private int next;
    private final Reader reader;
    private int size;
    private int state = ELEMENT_NOT_READ;
    private Token token;
    private char[] word;

    public LispLexer(Reader reader) {
        this.reader = reader;

        //@formatter:off
        buffer = new char[DEFAULT_BUFFER_SIZE];
        word   = new char[MAX_WORD_SIZE];
        //@formatter:on
    }

    private boolean charAvailable() throws IOException {
        if (next >= size) {
            if ((size = reader.read(buffer, 0, buffer.length)) < 0) {
                return false;
            }

            next = 0;
        }

        return true;
    }

    private void checkState() {
        if (state == CLOSED) {
            throw new IllegalStateException("The Lexer has already been closed.");
        }
    }

    public void close() throws IOException {
        if (state == CLOSED) {
            return;
        }

        reader.close();
    }

    private int columnOf(int c) {
        //@formatter:off
        switch (c) {
            case  -1: return 5;
            case '(': return 2;
            case ')': return 3;
            default: if (Character.isDigit(c))     return 1;
                     if (Character.isSpaceChar(c)) return 4;
                     return 0;
        }
        //@formatter:on
    }

    private boolean hasNext() throws IOException, LispLexerException {
        checkState();

        if (state == ELEMENT_READ) {
            return true;
        } else if (state == NO_SUCH_ELEMENT) {
            return false;
        }

        boolean read = scan();
        state = read ? ELEMENT_READ : NO_SUCH_ELEMENT;

        return read;
    }

    private static Iterator<Token> iterator(LispLexer lexer) {
        return new Iterator<Token>() {
            @Override
            public boolean hasNext() {
                //@formatter:off
                try                          { return lexer.hasNext(); }
                catch (IOException e)        { throw new UncheckedIOException(e); }
                catch (LispLexerException e) { throw new RuntimeException(e); }
                //@formatter:on
            }

            @Override
            public Token next() {
                //@formatter:off
                try                          { return lexer.next(); }
                catch (IOException e)        { throw new UncheckedIOException(e); }
                catch (LispLexerException e) { throw new RuntimeException(e); }
                //@formatter:on
            }
        };
    }

    private Token next() throws IOException, LispLexerException {
        if (!hasNext()) {
            throw new NoSuchElementException();
        }

        Token next = token;
        token = null;
        state = ELEMENT_NOT_READ;

        return next;
    }

    private boolean scan() throws IOException, LispLexerException {
        int i = 0;
        int row = SOT;

        do {
            int c = charAvailable() ? buffer[next] : -1;
            int action = ACTIONS[row + columnOf(c)];

            if ((action & ACCM) != 0) {
                word[i++] = (char) c;
            } else if ((action & LPAR) != 0) {
                token = new LeftParenthesisToken();
            } else if ((action & RPAR) != 0) {
                token = new RightParenthesisToken();
            } else if ((action & WORD) != 0) {
                token = new WordToken(new String(word, 0, i));
            } else if ((action & NMBR) != 0) {
                token = new NumberToken(Integer.parseInt(new String(word, 0, i)));
            }

            if ((action & MOVE) != 0) {
                next++;
            }

            if (token != null) {
                return true;
            }

            row = action & STATE_MASK;
        } while (row < EOT);

        if (row == ERR) {
            throw new UnexpectedCharacterException();
        }

        return false;
    }

    public static Stream<Token> stream(File file, boolean parallel) throws FileNotFoundException {
        return stream(file, Charset.defaultCharset(), parallel);
    }

    public static Stream<Token> stream(File file, Charset charset, boolean parallel) throws FileNotFoundException {
        return stream(new InputStreamReader(new FileInputStream(file), charset), parallel);
    }

    public static Stream<Token> stream(Reader reader, boolean parallel) {
        LispLexer lexer = new LispLexer(reader);

        //@formatter:off
        return StreamSupport.stream(spliteratorUnknownSize(iterator(lexer), IMMUTABLE | NONNULL | ORDERED), parallel)
                            .onClose(() -> {
                                try {
                                    lexer.close();
                                } catch (IOException e) {
                                    throw new UncheckedIOException(e);
                                }
                            });
        //@formatter:on
    }
}
