package dazld.fast_edn;

import clojure.lang.*;
import java.io.*;
import java.lang.invoke.*;
import java.lang.reflect.Field;
import java.math.*;
import java.util.*;
import java.util.function.*;
import java.util.stream.*;

@SuppressWarnings("unchecked")
public class EdnParser {
  public final static Keyword TAG_KEY = Keyword.intern(null, "tag");
  public final static Keyword PARAM_TAGS_KEY = Keyword.intern(null, "param-tags");

  // Symbol, keyword and number tokens mean what clojure.lang.EdnReader says they
  // mean: anything off the fast paths is handed to its own interpretToken and
  // matchNumber, and ##NaN/##Inf/##-Inf are its boxed instances, so values from
  // both readers are `=`. A Clojure release that renames these fails class init.
  static final MethodHandle INTERPRET_TOKEN;
  static final MethodHandle MATCH_NUMBER;
  static final Double NAN;
  static final Double POSITIVE_INFINITY;
  static final Double NEGATIVE_INFINITY;

  static {
    try {
      MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(EdnReader.class, MethodHandles.lookup());
      MethodType type = MethodType.methodType(Object.class, String.class);
      INTERPRET_TOKEN = lookup.findStatic(EdnReader.class, "interpretToken", type);
      MATCH_NUMBER    = lookup.findStatic(EdnReader.class, "matchNumber", type);
      Field specialsField = Class.forName("clojure.lang.EdnReader$SymbolicValueReader").getDeclaredField("specials");
      specialsField.setAccessible(true);
      IPersistentMap specials = (IPersistentMap) specialsField.get(null);
      NAN               = (Double) specials.valAt(Symbol.intern("NaN"));
      POSITIVE_INFINITY = (Double) specials.valAt(Symbol.intern("Inf"));
      NEGATIVE_INFINITY = (Double) specials.valAt(Symbol.intern("-Inf"));
    } catch (ReflectiveOperationException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  // True for yyyy-MM-ddTHH:mm:ss[.f{1,9}] with Z or +-HH:mm, year 1583 or later,
  // seconds up to 59 and offset hours up to 17: the timestamps whose java.time
  // reading equals clojure.instant's. Every other #inst goes to clojure.instant.
  public static boolean isPlainTimestamp(CharSequence cs) {
    int n = cs.length();
    if (n < 20
        || !isDigits(cs, 0, 4) || cs.charAt(4) != '-'
        || !isDigits(cs, 5, 7) || cs.charAt(7) != '-'
        || !isDigits(cs, 8, 10) || cs.charAt(10) != 'T'
        || !isDigits(cs, 11, 13) || cs.charAt(13) != ':'
        || !isDigits(cs, 14, 16) || cs.charAt(16) != ':'
        || !isDigits(cs, 17, 19)
        || digitsValue(cs, 0, 4) < 1583
        || digitsValue(cs, 17, 19) > 59) {
      return false;
    }
    int i = 19;
    if (cs.charAt(i) == '.') {
      int fractionStart = ++i;
      while (i < n && cs.charAt(i) >= '0' && cs.charAt(i) <= '9') {
        ++i;
      }
      if (i == fractionStart || i - fractionStart > 9) {
        return false;
      }
    }
    if (i == n - 1) {
      return cs.charAt(i) == 'Z';
    }
    return i == n - 6
      && (cs.charAt(i) == '+' || cs.charAt(i) == '-')
      && isDigits(cs, i + 1, i + 3) && cs.charAt(i + 3) == ':' && isDigits(cs, i + 4, i + 6)
      && digitsValue(cs, i + 1, i + 3) <= 17
      && digitsValue(cs, i + 4, i + 6) <= 59;
  }

  static boolean isDigits(CharSequence cs, int from, int to) {
    for (int i = from; i < to; ++i) {
      char ch = cs.charAt(i);
      if (ch < '0' || ch > '9') {
        return false;
      }
    }
    return true;
  }

  static int digitsValue(CharSequence cs, int from, int to) {
    int value = 0;
    for (int i = from; i < to; ++i) {
      value = value * 10 + (cs.charAt(i) - '0');
    }
    return value;
  }

  public static boolean isNonConstituent(char ch) {
    return ch == '@' || ch == '`' || ch == '~';
  }

  public Object interpretToken(String s) {
    if (s.length() > 0 && isNonConstituent(s.charAt(0))) {
      throw new RuntimeException("Invalid leading character: " + s.charAt(0) + context());
    }
    for (int i = 1; i < s.length(); ++i) {
      if (isNonConstituent(s.charAt(i))) {
        throw new RuntimeException("Invalid constituent character: " + s.charAt(i) + context());
      }
    }
    try {
      return (Object) INTERPRET_TOKEN.invokeExact(s);
    } catch (RuntimeException e) {
      throw e;
    } catch (Throwable t) {
      throw Util.sneakyThrow(t);
    }
  }

  public Number matchNumber(String s) {
    Object n;
    try {
      n = (Object) MATCH_NUMBER.invokeExact(s);
    } catch (RuntimeException e) {
      throw e;
    } catch (Throwable t) {
      throw Util.sneakyThrow(t);
    }
    if (n == null) {
      throw new NumberFormatException("Invalid number: " + s + context());
    }
    return (Number) n;
  }

  public final boolean countLines;
  public final ILookup dataReaders;
  public final IFn     defaultDataReader;
  public final boolean throwOnEOF;
  public final Object  eofValue;

  public char[]   readBuf;
  public char[]   accumulator;
  public int      accumulatorLength;

  public Reader   reader;
  public int      readPos;
  public int      readGlobalPos;
  public int      readLen;
  public boolean  isEOF;
  public int      line;
  public int      column;
  public boolean  skipLF;
  public int      discardDepth;

  public EdnParser(boolean countLines, int bufferSize, ILookup dataReaders, IFn defaultDataReader, boolean throwOnEOF, Object eofValue, Reader reader) {
    this.countLines = countLines;
    this.dataReaders = dataReaders;
    this.defaultDataReader = defaultDataReader;
    this.throwOnEOF = throwOnEOF;
    this.eofValue = eofValue;

    this.readBuf = new char[bufferSize];
    this.accumulator = new char[32];
    this.accumulatorLength = 0;
    
    setReader(reader);
  }

  public EdnParser setReader(Reader reader) {
    this.reader = reader;
    this.readPos = 0;
    this.readGlobalPos = 0;
    this.readLen = 0;
    this.isEOF = false;

    this.line = 0;
    this.column = 0;
    this.skipLF = false;
    this.discardDepth = 0;
    return this;
  }


  ////////////
  // Reader //
  ////////////

  public void updateLineColumn(int until) {
    char[] buf = readBuf;
    for (int pos = 0; pos < until; ++pos) {
      char ch = buf[pos];

      if (skipLF && ch == '\n') {
        skipLF = false;
        continue;
      }

      if (ch == '\r') {
        skipLF = true;
        line = line + 1;
        column = 0;
      } else if (ch == '\n') {
        skipLF = false;
        line = line + 1;
        column = 0;
      } else {
        skipLF = false;
        column = column + 1;
      }
    }
  }

  public void nextBuffer() {
    if (!isEOF) {
      try {
        // for better context() in badly buffered readers (e.g. stdin)
        if (readLen <= readBuf.length / 2) {
          int readLenNew = reader.read(readBuf, readLen, readBuf.length - readLen);
          if (readLenNew == -1) {
            isEOF = true;
          } else {
            readLen += readLenNew;
          }
        } else {
          if (countLines) {
            updateLineColumn(readLen);
          }
          int readLenNew = reader.read(readBuf, 0, readBuf.length);
          if (readLenNew == -1) {
            isEOF = true;
          } else {
            readPos = 0;
            readGlobalPos += readLen;
            readLen = readLenNew;
          }
        }
      } catch (IOException e) {
        Util.sneakyThrow(e);
      }
    }
  }

  public int read() {
    if (!isEOF && readLen > readPos) {
      return readBuf[readPos++];
    }
    nextBuffer();
    return isEOF ? -1 : readBuf[readPos++];
  }

  public void unread() {
    assert readPos > 0 : "Expected readPos > 0, got readPos = " + readPos;
    readPos -= 1;
  }

  public int skip(IntPredicate pred) {
    while (!isEOF) {
      char[] buf = readBuf;
      int    pos = readPos;
      int    len = readLen;
      for (; pos < len; ++pos) {
        int ch = buf[pos];
        if (!pred.test(ch)) {
          readPos = pos + 1;
          return ch;
        }
      }
      readPos = pos;
      nextBuffer();
    }
    return -1;
  }

  public int skipWhitespace() {
    return skip(EdnParser::isWhitespace);
  }

  public boolean peekBoundary() {
    int ch = read();
    if (ch == -1) {
      return true;
    }
    unread();
    return isBoundary(ch);
  }

  public boolean compareNext(int ch, String s, String error) {
    if ((char) ch != s.charAt(0)) {
      return false;
    }

    for (int i = 1; i < s.length(); ++i) {
      ch = read();
      if (ch == -1 || (char) ch != s.charAt(i)) {
        if (ch != -1) {
          unread();
        }
        throw new RuntimeException(error + continueReadingUntilBoundary(s.substring(0, i)) + context());
      }
    }

    if (!peekBoundary()) {
      throw new RuntimeException(error + continueReadingUntilBoundary(s) + context());
    }

    return true;
  }
  
  public String context() {
    if (readLen <= 0) {
      return "";
    }

    int start = Math.min(readPos, readLen - 1);
    for (; start > Math.max(0, readPos - 100); --start) {
      int ch = readBuf[start];
      if (ch == '\n' || ch == '\r') {
        start = Math.min(start + 1, readPos);
        break;
      }
    }

    int end = readPos;
    for (; end < Math.min(readLen, readPos + 100); ++end) {
      int ch = readBuf[end];
      if (ch == '\n' || ch == '\r') {
        end = Math.max(end - 1, readPos);
        break;
      }
    }

    if (end == start) {
      return "";
    }

    int offset = readGlobalPos + readPos;
    String position = ", offset: " + offset;
    if (countLines) {
      updateLineColumn(readPos);
      position = ", line: " + (line + 1) + ", column: " + (column + 1) + position;
    }
    
    final char[] indentArray = new char[Math.max(0, readPos - start - 1)];
    Arrays.fill(indentArray, ' ');
    String indent = new String(indentArray);

    return position + ", context:\n" + new String(readBuf, start, end - start) + "\n" + indent + "^";
  }


  /////////////////
  // Accumulator //
  /////////////////

  public void accumulatorEnsureCapacity(int len) {
    if (len > accumulator.length) {
      accumulator = Arrays.copyOf(accumulator, Integer.highestOneBit(len) << 1);
    }
  }

  public void accumulatorAppend(char ch) {
    // TODO if accumulatorLength + 1 <= accumulator.length
    accumulatorEnsureCapacity(accumulatorLength + 1);
    accumulator[accumulatorLength] = ch;
    accumulatorLength += 1;
  }

  public void accumulatorAppend(char[] data, int start, int end) {
    int len = end - start;
    if (len > 0) {
      accumulatorEnsureCapacity(accumulatorLength + len);
      // TODO if (len < 5)
      System.arraycopy(data, start, accumulator, accumulatorLength, len);
      accumulatorLength += len;
    }
  }

  public String accumulatorToString() {
    return new String(accumulator, 0, accumulatorLength);
  }


  ////////////////
  // readString //
  ////////////////

  public String readString() {
    char[] buf   = readBuf;
    int    start = readPos;
    int    pos   = start;
    int    len   = readLen;
    for (; pos < len; ++pos) {
      char ch = buf[pos];
      if (ch == '"') {
        readPos = pos + 1;
        return new String(buf, start, pos - start);
      }
      if (ch == '\\') {
        break;
      }
    }
    readPos = pos;
    return readStringComplex(buf, start, pos);
  }

  public byte digit16(int ch) {
    if ('0' <= ch && ch <= '9') {
      return (byte) (ch - '0');
    }
    
    if ('a' <= ch && ch <= 'f') {
      return (byte) (ch - 'a' + 10);
    }
    
    if ('A' <= ch && ch <= 'F') {
      return (byte) (ch - 'A' + 10);
    }
    
    if (-1 == ch) {
      throw new RuntimeException("EOF while reading" + context());
    }

    throw new RuntimeException("Unexpected digit: " + ((char) ch) + context());
  }

  public char readUnicodeChar() {
    char[] buf = readBuf;
    int pos = readPos;
    if (pos + 4 < readLen) {
      int ch = (digit16(buf[pos    ]) << 12)
             + (digit16(buf[pos + 1]) << 8)
             + (digit16(buf[pos + 2]) << 4)
             +  digit16(buf[pos + 3]);
      readPos = pos + 4;
      return (char) ch;
    }

    int ch = (digit16(read()) << 12)
           + (digit16(read()) << 8)
           + (digit16(read()) << 4)
           +  digit16(read());
    return (char) ch;
  }

  public char readOctalChar() {
    int value = 0;
    for (int i = 0; i < 3; ++i) {
      int ch = read();
      if (ch >= '0' && ch <= '7') {
        value = (value << 3) + (ch - '0');
      } else {
        unread();
        break;
      }
    }

    if (value > 0377) {
      throw new RuntimeException("Octal escape sequence must be in range [0, 377], got: " + Integer.toString(value, 8) + context());
    }
    
    return (char) value;
  }

  // Digits of a string's \\u or octal escape, read as EdnReader.readUnicodeChar
  // reads them: Character.digit, ending early only at whitespace or a macro char.
  public int readEscapeDigits(int initch, int base, int length, boolean exact) {
    int value = Character.digit(initch, base);
    if (value == -1) {
      throw new RuntimeException("Invalid digit: " + (char) initch + context());
    }
    int i = 1;
    for (; i < length; ++i) {
      int ch = read();
      if (ch == -1 || isWhitespace(ch) || isMacro(ch)) {
        if (ch != -1) {
          unread();
        }
        break;
      }
      int digit = Character.digit(ch, base);
      if (digit == -1) {
        throw new RuntimeException("Invalid digit: " + (char) ch + context());
      }
      value = value * base + digit;
    }
    if (i != length && exact) {
      throw new RuntimeException("Invalid character length: " + i + ", should be: " + length + context());
    }
    return value;
  }

  public String readStringComplex(char[] buf, int start, int pos) {
    accumulatorLength = 0;
    accumulatorAppend(buf, start, pos);

    outer:
    while (!isEOF) {
      buf     = readBuf;
      start   = readPos;
      pos     = start;
      int len = readLen;

      for (; pos < len; ++pos) {
        char ch1 = buf[pos];

        if (ch1 == '\\') {
          accumulatorAppend(buf, start, pos);
          readPos = pos + 1;
          int ch2 = read();

          if (ch2 == '"') {
            accumulatorAppend('"');
          } else if (ch2 == '\\') {
            accumulatorAppend('\\');
          } else if (ch2 == 'n') {
            accumulatorAppend('\n');
          } else if (ch2 == 'r') {
            accumulatorAppend('\r');
          } else if (ch2 == 'u') {
            int ch3 = read();
            if (Character.digit(ch3, 16) == -1) {
              throw new RuntimeException("Invalid unicode escape: \\u" + (char) ch3 + context());
            }
            accumulatorAppend((char) readEscapeDigits(ch3, 16, 4, true));
          } else if (ch2 == 't') {
            accumulatorAppend('\t');
          } else if (ch2 != -1 && Character.isDigit(ch2)) {
            int value = readEscapeDigits(ch2, 8, 3, false);
            if (value > 0377) {
              throw new RuntimeException("Octal escape sequence must be in range [0, 377], got: " + Integer.toString(value, 8) + context());
            }
            accumulatorAppend((char) value);
          } else if (ch2 == 'b') {
            accumulatorAppend('\b');
          } else if (ch2 == 'f') {
            accumulatorAppend('\f');
          } else if (ch2 == -1) {
            throw new RuntimeException("EOF reading string: " + context());
          } else {
            throw new RuntimeException("Unrecognized escape character while reading string: " + ((char) ch2) + context());
          }

          continue outer;
        } else if (ch1 == '"') {
          readPos = pos + 1;
          accumulatorAppend(buf, start, pos);
          return accumulatorToString();
        }
      }

      accumulatorAppend(buf, start, len);
      readPos = pos;
      nextBuffer();
    }

    throw new RuntimeException("EOF while reading string: \"" + accumulatorToString() + context());
  }


  ///////////////////
  // readCharacter //
  ///////////////////

  // Character literals as EdnReader.CharacterReader reads them: the token runs to
  // whitespace or a terminating macro char, and its digits go through Character.digit.
  public Character readCharacter() {
    int ch = read();

    if (-1 == ch) {
      throw new RuntimeException("EOF while reading character" + context());
    }

    StringBuilder sb = new StringBuilder();
    sb.append((char) ch);
    for (int next = read(); next != -1; next = read()) {
      if (isBoundary(next)) {
        unread();
        break;
      }
      if (isNonConstituent((char) next)) {
        throw new RuntimeException("Invalid constituent character: " + (char) next + context());
      }
      sb.append((char) next);
    }
    String token = sb.toString();

    if (token.length() == 1) {
      return Character.valueOf(token.charAt(0));
    } else if (token.equals("newline")) {
      return '\n';
    } else if (token.equals("space")) {
      return ' ';
    } else if (token.equals("tab")) {
      return '\t';
    } else if (token.equals("backspace")) {
      return '\b';
    } else if (token.equals("formfeed")) {
      return '\f';
    } else if (token.equals("return")) {
      return '\r';
    } else if (token.startsWith("u")) {
      char c = (char) tokenDigits(token, 1, 4, 16);
      if (c >= '\uD800' && c <= '\uDFFF') {
        throw new RuntimeException("Invalid character constant: \\u" + Integer.toString(c, 16) + context());
      }
      return c;
    } else if (token.startsWith("o")) {
      int len = token.length() - 1;
      if (len > 3) {
        throw new RuntimeException("Invalid octal escape sequence length: " + len + context());
      }
      int uc = tokenDigits(token, 1, len, 8);
      if (uc > 0377) {
        throw new RuntimeException("Octal escape sequence must be in range [0, 377]." + context());
      }
      return (char) uc;
    }
    throw new RuntimeException("Unsupported character: \\" + token + context());
  }

  public int tokenDigits(String token, int offset, int length, int base) {
    if (token.length() != offset + length) {
      throw new IllegalArgumentException("Invalid unicode character: \\" + token + context());
    }
    int value = 0;
    for (int i = offset; i < offset + length; ++i) {
      int digit = Character.digit(token.charAt(i), base);
      if (digit == -1) {
        throw new IllegalArgumentException("Invalid digit: " + token.charAt(i) + context());
      }
      value = value * base + digit;
    }
    return value;
  }


  ////////////////
  // readSymbol //
  ////////////////
  
  public Object readSymbol() {
    char[] buf   = readBuf;
    int    start = readPos;
    int    pos   = start;
    int    len   = readLen;
    int    slash = -1;

    for (; pos < len; ++pos) {
      char ch = buf[pos];
      if (isBoundary(ch)) {
        readPos = pos;
        return finalizeSymbol(buf, start, slash, pos);
      } else if (ch == '/' && slash == -1) {
        slash = pos;
      }
    }

    readPos = pos;
    return readSymbolComplex(buf, start, slash, pos);
  }

  public Object readSymbolComplex(char[] buf, int start, int slash, int pos) {
    accumulatorLength = 0;
    accumulatorAppend(buf, start, pos);
    if (slash != -1) {
      slash = slash - start;
    }

    outer:
    while (!isEOF) {
      buf     = readBuf;
      start   = readPos;
      pos     = start;
      int len = readLen;

      for (; pos < len; ++pos) {
        char ch = buf[pos];
        if (isBoundary(ch)) {
          accumulatorAppend(buf, start, pos);
          readPos = pos;
          break outer;
        } else if (ch == '/' && slash == -1) {
          slash = accumulatorLength + pos - start;
        }
      }

      accumulatorAppend(buf, start, len);
      readPos = pos;
      nextBuffer();
    }

    return finalizeSymbol(accumulator, 0, slash, accumulatorLength);
  }

  public Object continueReadingSymbol(char ch) {
    if (readPos > 0) {
      readPos = readPos - 1;
      return readSymbol();
    } else {
      accumulator[0] = ch;
      return readSymbolComplex(accumulator, 0, -1, 1);
    }
  }

  public String continueReadingUntilBoundary(String alreadyRead) {
    StringBuilder sb = new StringBuilder(alreadyRead);
    int ch = read();
    while (ch != -1 && !isBoundary(ch)) {
      sb.append((char) ch);
      ch = read();
    }
    if (ch != -1) {
      unread();
    }
    return sb.toString();
  }

  public Object finalizeSymbol(char[] buf, int start, int slash, int end) {
    int len = end - start;

    if (3 == len && buf[start] == 'n' && buf[start + 1] == 'i' && buf[start + 2] == 'l') {
      return null;
    }

    if (4 == len && buf[start] == 't' && buf[start + 1] == 'r' && buf[start + 2] == 'u' && buf[start + 3] == 'e') {
      return Boolean.TRUE;
    }

    if (5 == len && buf[start] == 'f' && buf[start + 1] == 'a' && buf[start + 2] == 'l' && buf[start + 3] == 's' && buf[start + 4] == 'e') {
      return Boolean.FALSE;
    }

    return interpretToken(new String(buf, start, len));
  }


  ////////////////
  // readTagged //
  ////////////////

  public Object readTagged() {
    Object tag = readObject(true);

    if (tag instanceof Symbol) {
      Object value = readObject(true);

      IFn dataReader = (IFn) RT.get(dataReaders, tag);

      if (dataReader != null) {
        return dataReader.invoke(value);
      } else if (defaultDataReader != null) {
        return defaultDataReader.invoke(tag, value);
      }
    }

    throw new RuntimeException("No dispatch macro for: #" + tag + context());
  }


  /////////////////
  // readKeyword //
  /////////////////

  public Keyword readKeyword() {
    char[] buf     = readBuf;
    int    start   = readPos;
    int    pos     = start;
    int    len     = readLen;
    int    hash    = 0;
    int    classes = 0;
    for (; pos < len; ++pos) {
      char ch = buf[pos];
      if (ch < 0x80) {
        int cls = CHAR_CLASS[ch];
        if ((cls & C_BOUNDARY) != 0) {
          break;
        }
        classes |= cls;
      } else if (Character.isWhitespace(ch)) {
        break;
      }
      hash = 31 * hash + ch;
    }
    if (pos < len) {
      readPos = pos;
      if (pos == start || (classes & C_KEYWORD_SPECIAL) != 0) {
        return finalizeKeyword(buf, start, -1, pos);
      }
      return internPlainKeyword(buf, start, pos, hash);
    }
    readPos = pos;
    return readKeywordComplex(buf, start, -1, pos);
  }

  public Keyword readKeywordComplex(char[] buf, int start, int slash, int pos) {
    accumulatorLength = 0;
    accumulatorAppend(buf, start, pos);
    if (slash != -1) {
      slash = slash - start;
    }

    outer:
    while (!isEOF) {
      buf     = readBuf;
      start   = readPos;
      pos     = start;
      int len = readLen;

      for (; pos < len; ++pos) {
        char ch = buf[pos];
        if (isBoundary(ch)) {
          accumulatorAppend(buf, start, pos);
          readPos = pos;
          break outer;
        } else if (ch == '/' && slash == -1) {
          slash = accumulatorLength + pos - start;
        }
      }

      accumulatorAppend(buf, start, len);
      readPos = pos;
      nextBuffer();
    }

    return finalizeKeyword(accumulator, 0, slash, accumulatorLength);
  }

  public Keyword finalizeKeyword(char[] buf, int start, int slash, int end) {
    if (end == start) {
      return (Keyword) interpretToken(":");
    }

    for (int i = start; i < end; ++i) {
      char ch = buf[i];
      if (ch == ':' || ch == '/' || isNonConstituent(ch)) {
        return (Keyword) interpretToken(":" + new String(buf, start, end - start));
      }
    }

    int hash = 0;
    for (int i = start; i < end; ++i) {
      hash = 31 * hash + buf[i];
    }
    return internPlainKeyword(buf, start, end, hash);
  }

  // Plain keyword names repeat heavily across documents; this table returns the
  // interned Keyword for a repeat without allocating its String or Symbol. Shared
  // by all parsers: an entry is immutable, so a racing overwrite only costs a miss.
  public static final class KeywordEntry {
    final char[]  chars;
    final Keyword keyword;

    KeywordEntry(char[] chars, Keyword keyword) {
      this.chars   = chars;
      this.keyword = keyword;
    }
  }

  public static final KeywordEntry[] KEYWORD_CACHE = new KeywordEntry[4096];

  public static Keyword internPlainKeyword(char[] buf, int start, int end, int hash) {
    int len  = end - start;
    int slot = (hash ^ (hash >>> 16)) & (KEYWORD_CACHE.length - 1);
    KeywordEntry entry = KEYWORD_CACHE[slot];
    if (entry != null && Arrays.equals(entry.chars, 0, entry.chars.length, buf, start, end)) {
      return entry.keyword;
    }
    char[]  chars = Arrays.copyOfRange(buf, start, end);
    Keyword kw    = Keyword.intern(Symbol.intern(null, new String(chars)));
    KEYWORD_CACHE[slot] = new KeywordEntry(chars, kw);
    return kw;
  }


  ////////////////
  // readNumber //
  ////////////////

  public Number readNumber() {
    return readNumber(false);
  }

  public Number readNumberNegative() {
    return readNumber(true);
  }

  public Number readNumber(boolean negative) {
    char[] buf   = readBuf;
    int    start = readPos;
    int    pos   = start;
    int    len   = readLen;
    long   val   = 0;

    for (; pos < len; ++pos) {
      char ch = buf[pos];
      if (ch >= '0' && ch <= '9') {
        val = val * 10 + ch - '0';
      } else if (isNumberBoundary(ch)) {
        int digits = pos - start;
        // empty, octal, or can overflow long
        if (digits == 0 || (digits > 1 && buf[start] == '0') || digits >= 19) {
          break;
        }
        readPos = pos;
        return negative ? -val : val;
      } else {
        break;
      }
    }

    readPos = pos;
    return readNumberToken(buf, start, pos, negative);
  }

  public Number readNumberToken(char[] buf, int start, int pos, boolean negative) {
    accumulatorLength = 0;
    if (negative) {
      accumulatorAppend('-');
    }
    accumulatorAppend(buf, start, pos);

    outer:
    while (!isEOF) {
      buf     = readBuf;
      start   = readPos;
      pos     = start;
      int len = readLen;

      for (; pos < len; ++pos) {
        if (isNumberBoundary(buf[pos])) {
          accumulatorAppend(buf, start, pos);
          readPos = pos;
          break outer;
        }
      }

      accumulatorAppend(buf, start, len);
      readPos = pos;
      nextBuffer();
    }

    if (isPlainFloat(accumulator, accumulatorLength)) {
      return Double.parseDouble(new String(accumulator, 0, accumulatorLength));
    }
    return matchNumber(accumulatorToString());
  }

  // True when the token matches EdnReader's floatPat without its M suffix and
  // has a '.' or exponent, so intPat cannot match it: [-+]?[0-9]+(\.[0-9]*)?([eE][-+]?[0-9]+)?
  public static boolean isPlainFloat(char[] buf, int len) {
    int i = 0;
    if (i < len && (buf[i] == '-' || buf[i] == '+')) {
      ++i;
    }
    int digitsStart = i;
    while (i < len && buf[i] >= '0' && buf[i] <= '9') {
      ++i;
    }
    if (i == digitsStart) {
      return false;
    }
    boolean floaty = false;
    if (i < len && buf[i] == '.') {
      floaty = true;
      ++i;
      while (i < len && buf[i] >= '0' && buf[i] <= '9') {
        ++i;
      }
    }
    if (i < len && (buf[i] == 'e' || buf[i] == 'E')) {
      floaty = true;
      ++i;
      if (i < len && (buf[i] == '-' || buf[i] == '+')) {
        ++i;
      }
      int expStart = i;
      while (i < len && buf[i] >= '0' && buf[i] <= '9') {
        ++i;
      }
      if (i == expStart) {
        return false;
      }
    }
    return floaty && i == len;
  }

  public Double readSymbolicValue() {
    int ch = read();

    if (ch == -1) {
      throw new RuntimeException("EOF while reading symbolic value" + context());
    }

    // important that all constants start with different letter
    if (compareNext(ch, "Inf", "Unknown symbolic value: ##")) {
      return POSITIVE_INFINITY;
    } else if (compareNext(ch, "-Inf", "Unknown symbolic value: ##")) {
      return NEGATIVE_INFINITY;
    } else if (compareNext(ch, "NaN", "Unknown symbolic value: ##")) {
      return NAN;
    }

    String symbol = continueReadingUntilBoundary(String.valueOf((char) ch));
    throw new RuntimeException("Unknown symbolic value: ##" + symbol + context());
  }


  //////////////
  // readList //
  //////////////

  public IPersistentList readList() {
    ArrayList acc = new ArrayList();

    while (!isEOF) {
      Object o = readObjectSafe(throwOnEOF);
      if (o instanceof UnexpectedCharacter) {
        if (((UnexpectedCharacter) o).ch == ')') {
          IPersistentList res = PersistentList.EMPTY;
          for (ListIterator i = acc.listIterator(acc.size()); i.hasPrevious(); ) {
            res = (IPersistentList) res.cons(i.previous());
          }
          return res;
        } else {
          throw new RuntimeException("Unmatched delimiter: "  + ((char) ((UnexpectedCharacter) o).ch));
        }
      } else {
        acc.add(o);
      }
    }

    throw new RuntimeException("EOF while reading list: " + toUnfinishedCollString(acc) + context());
  }


  ////////////////
  // readVector //
  ////////////////

  public PersistentVector readVector() {
    ITransientCollection acc = PersistentVector.EMPTY.asTransient();

    while (!isEOF) {
      Object o = readObjectSafe(throwOnEOF);

      if (o instanceof UnexpectedCharacter) {
        if (((UnexpectedCharacter) o).ch == ']') {
          return (PersistentVector) acc.persistent();
        } else {
          throw new RuntimeException("Unmatched delimiter: " + ((char) ((UnexpectedCharacter) o).ch));
        }
      } else {
        acc = acc.conj(o);
      }
    }

    throw new RuntimeException("EOF while reading vector: " + toUnfinishedCollString(acc.persistent()) + context());
  }


  /////////////
  // readSet //
  /////////////

  public PersistentHashSet readSet() {
    ATransientSet acc = (ATransientSet) PersistentHashSet.EMPTY.asTransient();
    int count = 0;

    while (!isEOF) {
      Object o = readObjectSafe(throwOnEOF);

      if (o instanceof UnexpectedCharacter) {
        if (((UnexpectedCharacter) o).ch == '}') {
          return (PersistentHashSet) acc.persistent();
        } else {
          throw new RuntimeException("Unmatched delimiter: " + ((char) ((UnexpectedCharacter) o).ch));
        }
      } else {
        acc = (ATransientSet) acc.conj(o);
        if (count + 1 != acc.count()) {
          throw new RuntimeException("Duplicate key: " + o + " reading set: " + toUnfinishedCollString(acc.persistent()) + context());
        }
        count = count + 1;
      }
    }

    throw new RuntimeException("EOF while reading set: " + toUnfinishedCollString(acc.persistent()) + context());
  }


  /////////////
  // readMap //
  /////////////

  public IPersistentMap readMap(String ns) {
    ATransientMap acc = (ATransientMap) PersistentArrayMap.EMPTY.asTransient();
    
    int count = 0;

    while (!isEOF) {
      Object key = readObjectSafe(throwOnEOF);
      if (key instanceof UnexpectedCharacter) {
        if (((UnexpectedCharacter) key).ch == '}') {
          return acc.persistent();
        } else {
          throw new RuntimeException("Unmatched delimiter: " + ((char) ((UnexpectedCharacter) key).ch));
        }
      } else {
        if (ns != null) {
          if (key instanceof Keyword) {
            Keyword kw = (Keyword) key;
            if (kw.getNamespace() == null) {
              key = Keyword.intern(ns, kw.getName());
            } else if (kw.getNamespace().equals("_")) {
              key = Keyword.intern(null, kw.getName());
            }
          } else if (key instanceof Symbol) {
            Symbol s = (Symbol) key;
            if (s.getNamespace() == null) {
              key = Symbol.intern(ns, s.getName());
            } else if (s.getNamespace().equals("_")) {
              key = Symbol.intern(null, s.getName());
            }
          }
        }

        Object val = readObjectSafe(throwOnEOF);
        if (val instanceof UnexpectedCharacter) {
          if (((UnexpectedCharacter) val).ch == '}') {
            throw new RuntimeException("Map literal must contain an even number of forms: " + toUnfinishedCollString(acc.persistent()) + ", " + key + context());
          } else {
            throw new RuntimeException("Unmatched delimiter: " + ((char) ((UnexpectedCharacter) val).ch));
          }
        }

        acc = (ATransientMap) acc.assoc(key, val);
        count = count + 1;
        if (acc.count() != count) {
          throw new RuntimeException("Duplicate key: " + key + " reading map: " + toUnfinishedCollString(acc.persistent()) + context());
        }
      }
    }

    throw new RuntimeException("EOF while reading map: " + toUnfinishedCollString(acc.persistent()) + context());
  }


  //////////////
  // readMeta //
  //////////////

  public Object readMeta() {
    Object meta = readObject(true);

    if (meta instanceof Symbol || meta instanceof String) {
      meta = RT.map(TAG_KEY, meta);
    } else if (meta instanceof Keyword) {
      meta = RT.map(meta, RT.T);
    } else if (!(meta instanceof IPersistentMap)) {
      throw new RuntimeException("Metadata must be Symbol, Keyword, String or Map, got " + toClassString(meta) + context());
    }

    Object obj = readObject(true);
    if (!(obj instanceof IMeta)) {
      throw new RuntimeException("Metadata can only be applied to IMetas, got " + toClassString(obj) + context());
    }

    IPersistentMap objMeta = (IPersistentMap) RT.meta(obj);
    meta = ((IKVReduce) meta).kvreduce(new AFn() {
      @Override
      public Object invoke(Object objMeta, Object key, Object obj) {
        return RT.assoc(objMeta, key, obj);
      }
    }, objMeta);

    return ((IObj) obj).withMeta((IPersistentMap) meta);
  }


  ////////////////
  // readObject //
  ////////////////

  public Object readNext() {
    try {
      return readObject(throwOnEOF);
    } catch (Exception e) {
      String message = e.getMessage();
      // exceptions thrown by the parser itself already carry context()
      if (message != null && message.contains(", offset: ")) {
        throw Util.sneakyThrow(e);
      }
      Exception e2 = null;
      try {
        e2 = e.getClass().getDeclaredConstructor(String.class).newInstance(message + context());
        e2.setStackTrace(e.getStackTrace());
      } catch (Exception t3) {
        throw Util.sneakyThrow(e);
      }
      throw Util.sneakyThrow(e2);
    }
  }

  public Object readObject() {
    return readObject(throwOnEOF);
  }

  public Object readObject(boolean throwOnEOF) {
    Object o = readObjectSafe(throwOnEOF);

    if (!(o instanceof UnexpectedCharacter)) {
      return o;
    }

    int ch = ((UnexpectedCharacter) o).ch;
    throw new RuntimeException("Unexpected character: " + ((char) ch) + context());
  }

  public Object readObjectSafe(boolean throwOnEOF) {
    if (reader == null) {
      return null;
    }

    while (true) {
      int ch1 = skipWhitespace();

      switch (ch1) {
        case '"': {
          return readString();
        }

        case ':': {
          return readKeyword();
        }

        case '{': {
          return readMap(null);
        }

        case '[': {
          return readVector();
        }

        case '(': {
          return readList();
        }

        case ';': {
          skip(ch -> '\n' != ch && '\r' != ch);
          continue;
        }

        case '-': {
          int ch2 = read();
          unread();

          if (-1 == ch2 || isBoundary(ch2)) {
            return Symbol.intern(null, "-");
          } else if (Character.isDigit(ch2)) {
            return readNumberNegative();
          } else {
            return continueReadingSymbol('-');
          }
        }

        case '^': {
          return readMeta();
        }

        case '\\': {
          return readCharacter();
        }

        case '+': {
          int ch2 = read();
          unread();

          if (-1 == ch2 || isBoundary(ch2)) {
            return Symbol.intern(null, "+");
          } else if (Character.isDigit(ch2)) {
            return readNumber();
          } else {
            return continueReadingSymbol('+');
          }
        }

        case '#': {
          int ch2 = read();

          if (ch2 == -1) {
            throw new RuntimeException("EOF while reading dispatch macro" + context());
          }

          if (ch2 == '{') {
            return readSet();
          }

          if (ch2 == '_') {
            discardDepth += 1;
            try {
              readObject(true);
            } finally {
              discardDepth -= 1;
            }
            continue;
          }

          if (ch2 == '#') {
            return readSymbolicValue();
          }

          if (ch2 == '^') {
            return readMeta();
          }

          if (ch2 == ':') {
            Object ns = readObject(true);
            if (!(ns instanceof Symbol) || ((Symbol) ns).getNamespace() != null) {
              throw new RuntimeException("Namespaced map must specify a valid namespace: " + ns + context());
            }

            int ch4 = skipWhitespace();
            if (ch4 != '{') {
              throw new RuntimeException("Namespaced map must specify a map: " + ns + context());
            }

            return readMap(((Symbol) ns).getName());
          }

          unread();
          return readTagged();
        }

        case -1: {
          if (throwOnEOF) {
            throw new RuntimeException("EOF while reading" + context());
          } else {
            return eofValue;
          }
        }

        default: {
          if (Character.isDigit(ch1)) {
            unread();
            return readNumber();
          } else if (!isBoundary(ch1)) {
            unread();
            return readSymbol();
          }

          return new UnexpectedCharacter(ch1);
        }
      }
    }
  }


  //////////
  // Misc //
  //////////

  public static final BitSet whitespaceMask = new BitSet(0x30);
  public static final BitSet boundaryMask = new BitSet(0x80);
  public static final BitSet numberBoundaryMask = new BitSet(0x80);
  public static final BitSet macroMask = new BitSet(0x80);

  public static final byte   C_WHITESPACE       = 1;
  public static final byte   C_BOUNDARY         = 2;
  public static final byte   C_NUMBER_BOUNDARY  = 4;
  public static final byte   C_MACRO            = 8;
  // a keyword holding one of : / @ ` ~ is interpreted by EdnReader
  public static final byte   C_KEYWORD_SPECIAL  = 16;
  public static final byte[] CHAR_CLASS         = new byte[0x80];
  
  static {
    // ASCII whitespace as in Character.isWhitespace
    whitespaceMask.set('\t');
    whitespaceMask.set('\n');
    whitespaceMask.set(0x0B); // VERTICAL TAB
    whitespaceMask.set('\f');
    whitespaceMask.set('\r');
    whitespaceMask.set(0x1C, 0x20); // FILE/GROUP/RECORD/UNIT SEPARATOR
    whitespaceMask.set(' ');
    whitespaceMask.set(',');

    boundaryMask.or(whitespaceMask);
    boundaryMask.set('"');
    boundaryMask.set('(');
    boundaryMask.set(')');
    boundaryMask.set(';');
    boundaryMask.set('[');
    boundaryMask.set('\\');
    boundaryMask.set(']');
    boundaryMask.set('^');
    boundaryMask.set('{');
    boundaryMask.set('}');

    numberBoundaryMask.or(boundaryMask);
    numberBoundaryMask.set('#');

    macroMask.or(numberBoundaryMask);
    macroMask.andNot(whitespaceMask);

    for (int ch = 0; ch < 0x80; ++ch) {
      CHAR_CLASS[ch] = (byte) ((whitespaceMask.get(ch) ? C_WHITESPACE : 0)
                             | (boundaryMask.get(ch) ? C_BOUNDARY : 0)
                             | (numberBoundaryMask.get(ch) ? C_NUMBER_BOUNDARY : 0)
                             | (macroMask.get(ch) ? C_MACRO : 0));
    }
    for (char ch : new char[] {':', '/', '@', '`', '~'}) {
      CHAR_CLASS[ch] |= C_KEYWORD_SPECIAL;
    }
  }

  public static boolean isWhitespace(int ch) {
    return ch < 0x80 ? (ch >= 0 && (CHAR_CLASS[ch] & C_WHITESPACE) != 0) : Character.isWhitespace(ch);
  }

  public static boolean isMacro(int ch) {
    return ch >= 0 && ch < 0x80 && (CHAR_CLASS[ch] & C_MACRO) != 0;
  }

  public static boolean isHexDigit(int ch) {
    return ('0' <= ch && ch <= '9') || ('a' <= ch && ch <= 'f') || ('A' <= ch && ch <= 'F');
  }

  public static boolean isBoundary(int ch) {
    return ch < 0x80 ? (ch >= 0 && (CHAR_CLASS[ch] & C_BOUNDARY) != 0) : Character.isWhitespace(ch);
  }

  public static boolean isNumberBoundary(int ch) {
    return ch < 0x80 ? (ch >= 0 && (CHAR_CLASS[ch] & C_NUMBER_BOUNDARY) != 0) : Character.isWhitespace(ch);
  }

  public static String toClassString(Object o) {
    if (o == null) {
      return "null";
    }

    return o.getClass().getName() + ": " + o.toString();
  }

  public static String toUnfinishedCollString(Object o) {
    if (o instanceof APersistentVector) {
      return (String) ((APersistentVector) o).stream().map(String::valueOf).collect(Collectors.joining(" ", "[", ""));
    }

    if (o instanceof APersistentSet) {
      return (String) ((APersistentSet) o).stream().map(String::valueOf).collect(Collectors.joining(" ", "#{", ""));
    }

    if (o instanceof APersistentMap) {
      return (String) ((Map<Object, Object>) o).entrySet().stream().map((Map.Entry e) -> String.valueOf(e.getKey()) + " " + String.valueOf(e.getValue())).collect(Collectors.joining(", ", "{", ""));
    }

    if (o instanceof List) {
      return (String) ((List) o).stream().map(String::valueOf).collect(Collectors.joining(" ", "(", ""));
    }

    throw new RuntimeException("Unknown object type " + toClassString(o));
  }

  public static byte digit10(CharSequence cs, int i) {
    char ch = cs.charAt(i);
    if (ch < '0' || ch > '9') {
      throw new RuntimeException("Unexpected character at pos " + i + ": " + ch + " parsing \"" + cs + "\"");
    }
    return (byte) (ch - '0');
  }

  public static Object parseTimestamp(IFn newTimestamp, CharSequence cs) {
    int year = 0;
    int months = 1;
    int days = 1;
    int hours = 0;
    int minutes = 0;
    int seconds = 0;
    int nano = 0;
    int len = cs.length();
    int pos = 0;
    int zoneSign = 1;
    int zoneHours = 0;
    int zoneMinutes = 0;

    if (len < 4) {
      throw new RuntimeException("Unexpected end of string before " + 4 + " parsing \"" + cs + "\"");
    }

    year = digit10(cs, 0) * 1000 + digit10(cs, 1) * 100 + digit10(cs, 2) * 10 + digit10(cs, 3);
    pos += 4;

    if (len >= 7 && cs.charAt(4) == '-') {
      months = digit10(cs, 5) * 10 + digit10(cs, 6);
      pos += 3;
    }

    if (len >= 10 && cs.charAt(7) == '-') {
      days = digit10(cs, 8) * 10 + digit10(cs, 9);
      pos += 3;
    }

    if (len >= 13 && cs.charAt(10) == 'T') {
      hours = digit10(cs, 11) * 10 + digit10(cs, 12);
      pos += 3;
    }

    if (len >= 16 && cs.charAt(13) == ':') {
      minutes = digit10(cs, 14) * 10 + digit10(cs, 15);
      pos += 3;
    }

    if (len >= 19 && cs.charAt(16) == ':') {
      seconds = digit10(cs, 17) * 10 + digit10(cs, 18);
      pos += 3;
    }

    if (pos < len && cs.charAt(pos) == '.') {
      pos = pos + 1;
      int mult = 100000000;
      for (; pos < len && mult > 0; ++pos) {
        char ch = cs.charAt(pos);
        if (ch >= '0' && ch <= '9') {
          nano = nano + (ch - '0') * mult;
          mult = mult / 10;
        } else {
          break;
        }
      }
    }

    if (pos < len) {
      switch (cs.charAt(pos)) {
        case 'Z':
          pos = pos + 1;
          break;

        case '-':
          zoneSign = -1;
          // fall through intentional

        case '+':
          if (pos + 5 >= len) {
            throw new RuntimeException("Unexpected end of string before " + (pos + 5) + " parsing \"" + cs + "\"");
          }
          zoneHours = digit10(cs, pos + 1) * 10 + digit10(cs, pos + 2);
          if (':' != cs.charAt(pos + 3)) {
            throw new RuntimeException("Unexpected character at pos " + (pos + 3) + ": " + cs.charAt(pos + 3) + " parsing \"" + cs + "\"");
          }
          zoneMinutes = digit10(cs, pos + 4) * 10 + digit10(cs, pos + 5);
          pos = pos + 6;
          break;

        default:
          throw new RuntimeException("Unexpected character at pos " + pos + ": " + cs.charAt(pos) + " parsing \"" + cs + "\"");
      }
    }

    if (pos < len) {
      throw new RuntimeException("Unexpected character at pos " + pos + ": " + cs.charAt(pos) + " parsing \"" + cs + "\"");
    }

    return newTimestamp.invoke(year, months, days, hours, minutes, seconds, nano, zoneSign, zoneHours, zoneMinutes);
  }

  public static class UnexpectedCharacter {
    public final int ch;

    public UnexpectedCharacter(int ch) {
      this.ch = ch;
    }
  }
}
