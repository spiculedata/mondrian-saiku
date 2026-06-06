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

import com.google.common.base.Charsets;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.net.URL;

import static java.util.Objects.requireNonNull;

/** Utilities for {@link Source}. */
public class Sources {
  private Sources() {}

  /** Creates a source that reads from a URL. */
  public static Source fromUrl(URL url) {
    return new UrlSource(url);
  }

  public static Source fromString(String code) {
    return new StringSource(code);
  }

  /** Source backed by a URL. */
  static class UrlSource implements Source {
    private final URL url;
    /** Cache of url.toString(). It is unsafe to apply equals or hashCode
     * to a URL, so we work on the externalized form. */
    private final String urlString; // cache of url.toString()

    UrlSource(URL url) {
      this.url = requireNonNull(url, "url");
      this.urlString = url.toString();
    }

    @Override public int hashCode() {
      return urlString.hashCode();
    }

    @Override public boolean equals(Object o) {
      return this == o
          || o instanceof UrlSource
          && urlString.equals(((UrlSource) o).urlString);
    }

    @Override public String toString() {
      return urlString;
    }

    @Override public boolean preferStream() {
      return true;
    }

    @Override public InputStream inputStream() throws IOException {
      return url.openStream();
    }

    @Override public String contentsAsString() {
      try (InputStream stream = url.openStream();
           Reader r = new InputStreamReader(stream, Charsets.ISO_8859_1)) {
        return readerToString(r);
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }

    private String readerToString(Reader r) throws IOException {
      final char[] buf = new char[2048];
      final StringBuilder sb = new StringBuilder();
      for (;;) {
        int c = r.read(buf);
        if (c < 0) {
          break;
        }
        sb.append(buf, 0, c);
      }
      return sb.toString();
    }
  }

  /** Source backed by a string. */
  private static class StringSource implements Source {
    private final String code;

    StringSource(String code) {
      this.code = requireNonNull(code, "code");
    }

    @Override public int hashCode() {
      return code.hashCode();
    }

    @Override public boolean equals(Object o) {
      return o == this
          || o instanceof StringSource
          && code.equals(((StringSource) o).code);
    }

    @Override public String toString() {
      return "<inline>";
    }

    @Override public boolean preferStream() {
      return false; // we prefer Reader
    }

    @Override public Reader reader() {
      return new StringReader(code);
    }

    @Override public String contentsAsString() {
      return code;
    }
  }
}

// End Sources.java
