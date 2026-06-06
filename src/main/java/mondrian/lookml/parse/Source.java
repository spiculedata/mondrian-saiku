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

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;

/** Location from which parser reads input.
 *
 * <p>A source generalizes string, file, and URL. It is part of a position
 * ({@link Pos}) and is reported as part of the location when an error occurs.
 *
 * @see Sources */
public interface Source {
  /** Whether to prefer {@link #inputStream()} over {@link #reader()}. */
  boolean preferStream();

  /** Opens a reader from this source. */
  default Reader reader() {
    throw new UnsupportedOperationException();
  }

  /** Opens an input stream from this source. */
  default InputStream inputStream() throws IOException {
    throw new UnsupportedOperationException();
  }

  /** Returns the contents of this source as a string. */
  String contentsAsString();
}

// End Source.java
