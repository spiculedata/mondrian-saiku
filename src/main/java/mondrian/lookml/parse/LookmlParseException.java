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
package mondrian.lookml.parse;

/**
 * Thrown when LookML cannot be parsed.
 *
 * <p>The message includes the source description and, where the underlying
 * JavaCC parser reported one, the line/column of the offending token.
 */
public class LookmlParseException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  /** Creates a LookmlParseException with a message and cause. */
  public LookmlParseException(String message, Throwable cause) {
    super(message, cause);
  }

  /** Creates a LookmlParseException with a message. */
  public LookmlParseException(String message) {
    super(message);
  }
}

// End LookmlParseException.java
