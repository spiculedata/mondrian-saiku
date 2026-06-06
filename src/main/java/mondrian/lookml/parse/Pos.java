/*
 * Licensed to the LookML Authors under one or more contributor
 * license agreements.  See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership.
 * The LookML Authors license this file to you under the Apache
 * License, Version 2.0 (the "License"); you may not use this
 * file except in compliance with the License.  You may obtain a
 * copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied.  See the License for the specific
 * language governing permissions and limitations under the
 * License.
 */
//
// Vendored from net.hydromatic:lookml (https://github.com/hydromatic/lookml),
// Apache-2.0. Re-packaged into mondrian.lookml.parse for the LookML->M4
// importer (issue #98). No functional changes.
//
package mondrian.lookml.parse;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.AbstractList;
import java.util.List;
import java.util.function.Function;

import static java.util.Objects.hash;

/** Position of a parse-tree node. */
public class Pos {
  public static final Pos ZERO = new Pos(Sources.fromString(""), 0, 0, 0, 0);

  public final Source source;
  public final int startLine;
  public final int startColumn;
  public final int endLine;
  public final int endColumn;

  /** Creates a Pos. */
  public Pos(Source source, int startLine, int startColumn,
      int endLine, int endColumn) {
    this.source = source;
    this.startLine = startLine;
    this.startColumn = startColumn;
    this.endLine = endLine;
    this.endColumn = endColumn;
  }

  /** Creates a Pos from two offsets. */
  public static Pos of(String ml, Source source, int startOffset,
      int endOffset) {
    IntPair start = lineCol(ml, startOffset);
    IntPair end = lineCol(ml, endOffset);
    return new Pos(source, start.left, start.right, end.left, end.right);
  }

  /** Creates a Pos that is a copy of this but embeds another Pos. */
  public Pos embed(Pos subPos) {
    return new ParentPos(source, startLine, startColumn, endLine, endColumn,
        subPos);
  }

  /** Creates a Builder. */
  public static Builder builder(Source source, int startLine, int startColumn,
      int endLine, int endColumn) {
    return new Builder(source, startLine, startColumn, endLine, endColumn);
  }

  @Override public int hashCode() {
    return hash(startLine, startColumn, endLine, endColumn, source);
  }

  @Override public boolean equals(Object o) {
    return o == this
        || o instanceof Pos
        && this.startLine == ((Pos) o).startLine
        && this.startColumn == ((Pos) o).startColumn
        && this.endLine == ((Pos) o).endLine
        && this.endColumn == ((Pos) o).endColumn
        && this.source.equals(((Pos) o).source);
  }

  @Override public String toString() {
    return describeTo(new StringBuilder()).toString();
  }

  /** Returns a list of child positions.
   *
   * <p>The default implementation returns the empty list;
   * if you call {@code embed(p)} then the resulting {@code Pos}
   * has a singleton child {@code p}. */
  public List<Pos> children() {
    return ImmutableList.of();
  }

  public StringBuilder describeTo(StringBuilder buf) {
    buf.append(source);
    if (buf.length() > 0) {
      buf.append(':');
    }
    return describeTo2(buf);
  }

  public StringBuilder describeTo2(StringBuilder buf) {
    buf.append(startLine)
        .append('.')
        .append(startColumn);
    if (endColumn != startColumn + 1 || endLine != startLine) {
      buf.append('-')
          .append(endLine)
          .append('.')
          .append(endColumn);
    }
    return buf;
  }

  /**
   * Combines an iterable of parser positions to create a position which spans
   * from the beginning of the first to the end of the last.
   */
  public static Pos sum(Iterable<Pos> poses) {
    final List<Pos> list =
        poses instanceof List
            ? (List<Pos>) poses
            : Lists.newArrayList(poses);
    return sum_(list);
  }

  public static <E> Pos union(Iterable<E> elements, Function<E, Pos> fn) {
    //noinspection StaticPseudoFunctionalStyleMethod
    return sum(Iterables.transform(elements, fn::apply));
  }

  /**
   * Combines a list of parser positions to create a position which spans
   * from the beginning of the first to the end of the last.
   */
  private static Pos sum_(final List<Pos> positions) {
    switch (positions.size()) {
    case 0:
      throw new AssertionError();
    case 1:
      return positions.get(0);
    default:
      final List<Pos> poses = new AbstractList<Pos>() {
        public Pos get(int index) {
          return positions.get(index + 1);
        }
        public int size() {
          return positions.size() - 1;
        }
      };
      final Pos p = positions.get(0);
      return sum(poses, p.startLine, p.startColumn, p.endLine, p.endColumn);
    }
  }


  /**
   * Computes the parser position which is the sum of an array of parser
   * positions and of a parser position represented by (line, column, endLine,
   * endColumn).
   *
   * @param poses     Array of parser positions
   * @param line      Start line
   * @param column    Start column
   * @param endLine   End line
   * @param endColumn End column
   * @return Sum of parser positions
   */
  private static Pos sum(Iterable<Pos> poses, int line, int column, int endLine,
      int endColumn) {
    int testLine;
    int testColumn;
    Source source = Pos.ZERO.source;
    for (Pos pos : poses) {
      if (pos == null || pos.equals(Pos.ZERO)) {
        continue;
      }
      source = pos.source;
      testLine = pos.startLine;
      testColumn = pos.startColumn;
      if (testLine < line || testLine == line && testColumn < column) {
        line = testLine;
        column = testColumn;
      }

      testLine = pos.endLine;
      testColumn = pos.endColumn;
      if (testLine > endLine || testLine == endLine && testColumn > endColumn) {
        endLine = testLine;
        endColumn = testColumn;
      }
    }
    return new Pos(source, line, column, endLine, endColumn);
  }

  public Pos plus(Pos pos) {
    int startLine = this.startLine;
    int startColumn = this.startColumn;
    if (pos.startLine < startLine
        || pos.startLine == startLine
        && pos.startColumn < startColumn) {
      startLine = pos.startLine;
      startColumn = pos.startColumn;
    }
    int endLine = pos.endLine;
    int endColumn = pos.endColumn;
    if (this.endLine > endLine
        || this.endLine == endLine
        && this.endColumn > endColumn) {
      endLine = this.endLine;
      endColumn = this.endColumn;
    }
    return new Pos(source, startLine, startColumn, endLine, endColumn);
  }

  public Pos plusAll(Iterable<Pos> poses) {
    return sum(poses, startLine, startColumn, endLine, endColumn);
  }

  /** Returns the 1-based line. */
  private static IntPair lineCol(String s, int offset) {
    int line = 1;
    int lineStart = 0;
    int i;
    final int n = Math.min(s.length(), offset);
    for (i = 0; i < n; i++) {
      if (s.charAt(i) == '\n') {
        ++line;
        lineStart = i + 1;
      }
    }
    if (i == offset) {
      return new IntPair(line, offset - lineStart + 1);
    } else {
      throw new IllegalArgumentException("not found");
    }
  }

  /** An immutable pair of integers. */
  static class IntPair {
    public final int left;
    public final int right;

    /** Creates an IntPair. */
    IntPair(int left, int right) {
      this.left = left;
      this.right = right;
    }

    @Override public String toString() {
      return left + "-" + right;
    }

    @Override public boolean equals(@Nullable Object o) {
      return o == this
          || o instanceof IntPair
          && left == ((IntPair) o).left
          && right == ((IntPair) o).right;
    }

    @Override public int hashCode() {
      return hash(left, right);
    }
  }

  /** Mutable builder that creates a Pos. */
  public static class Builder {
    private final Source source;
    private final int startLine;
    private final int startColumn;
    private int endLine;
    private int endColumn;

    /** Creates a Builder. */
    private Builder(Source source, int startLine, int startColumn,
        int endLine, int endColumn) {
      this.source = source;
      this.startLine = startLine;
      this.startColumn = startColumn;
      this.endLine = endLine;
      this.endColumn = endColumn;
    }

    /** Creates a Builder with the same state as this Builder. */
    public Builder copy() {
      return new Builder(source, startLine, startColumn, endLine, endColumn);
    }

    /** Builds a Pos. */
    public Pos build() {
      return new Pos(source, startLine, startColumn, endLine, endColumn);
    }

    /** Extends the end of the position being built. */
    public void union(int endLine, int endColumn) {
      if (endLine > this.endLine
          || endLine == this.endLine && endColumn > this.endColumn) {
        this.endLine = endLine;
        this.endColumn = endColumn;
      }
    }
  }

  /** Position that contains a child position. */
  private static class ParentPos extends Pos {
    private final Pos subPos;

    ParentPos(Source source, int startLine, int startColumn,
        int endLine, int endColumn, Pos subPos) {
      super(source, startLine, startColumn, endLine, endColumn);
      this.subPos = subPos;
    }

    @Override public List<Pos> children() {
      return ImmutableList.of(subPos);
    }
  }
}

// End Pos.java
