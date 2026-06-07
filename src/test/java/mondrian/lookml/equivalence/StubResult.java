/*
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
package mondrian.lookml.equivalence;

import mondrian.olap.Axis;
import mondrian.olap.Cell;
import mondrian.olap.Member;
import mondrian.olap.Position;
import mondrian.olap.Result;

import java.util.ArrayList;
import java.util.List;

import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Issue #128: a minimal Mockito-backed {@link Result} for the comparator unit
 * tests — a single-measure result over one ROWS dimension. Each row is a
 * (member-name, value) pair; cell [0, r] returns the value, ROWS positions
 * carry the member name. Keeps the comparator tests engine-free.
 */
final class StubResult {

  private StubResult() {}

  static Builder builder() {
    return new Builder();
  }

  static final class Builder {
    private final List<String> names = new ArrayList<>();
    private final List<Double> values = new ArrayList<>();

    Builder row(String memberName, double value) {
      names.add(memberName);
      values.add(value);
      return this;
    }

    Result build() {
      final List<Position> positions = new ArrayList<>();
      for (String name : names) {
        final Member member = mock(Member.class);
        lenient().when(member.getName()).thenReturn(name);
        final Position pos = mock(Position.class);
        when(pos.size()).thenReturn(1);
        when(pos.get(0)).thenReturn(member);
        positions.add(pos);
      }
      final Axis rows = mock(Axis.class);
      when(rows.getPositions()).thenReturn(positions);
      // axes[0] = COLUMNS (one measure), axes[1] = ROWS.
      final Axis columns = mock(Axis.class);
      final Result result = mock(Result.class);
      when(result.getAxes()).thenReturn(new Axis[]{columns, rows});

      for (int r = 0; r < values.size(); r++) {
        final Cell cell = mock(Cell.class);
        when(cell.getValue()).thenReturn(values.get(r));
        when(result.getCell(new int[]{0, r})).thenReturn(cell);
      }
      return result;
    }
  }
}

// End StubResult.java
