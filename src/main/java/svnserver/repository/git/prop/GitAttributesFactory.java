/**
 * This file is part of git-as-svn. It is subject to the license terms
 * in the LICENSE file found in the top-level directory of this distribution
 * and at http://www.gnu.org/licenses/gpl-2.0.html. No part of git-as-svn,
 * including this file, may be copied, modified, propagated, or distributed
 * except according to the terms contained in the LICENSE file.
 */
package svnserver.repository.git.prop;

import org.eclipse.jgit.errors.InvalidPatternException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tmatesoft.svn.core.SVNProperty;
import org.tmatesoft.svn.core.internal.wc.SVNFileUtil;
import svnserver.repository.git.path.Wildcard;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.PatternSyntaxException;

/**
 * Factory for properties, generated by .gitattributes.
 *
 * @author Artem V. Navrotskiy <bozaro@users.noreply.github.com>
 */
@SuppressWarnings("UnusedDeclaration")
public final class GitAttributesFactory implements GitPropertyFactory {
  @NotNull
  private static final Logger log = LoggerFactory.getLogger(GitAttributesFactory.class);
  @NotNull
  private static final String EOL_PREFIX = "eol=";
  @NotNull
  private static final String FILTER_PREFIX = "filter=";

  @NotNull
  @Override
  public String getFileName() {
    return ".gitattributes";
  }

  @NotNull
  @Override
  public GitProperty[] create(@NotNull String content) throws IOException {
    final List<GitProperty> properties = new ArrayList<>();
    for (String line : content.split("(?:#[^\n]*)?\n")) {
      final String[] tokens = line.trim().split("\\s+");
      try {
        final Wildcard wildcard = new Wildcard(tokens[0]);
        processProperty(properties, wildcard, SVNProperty.MIME_TYPE, getMimeType(tokens));
        processProperty(properties, wildcard, SVNProperty.EOL_STYLE, getEol(tokens));

        final String filter = getFilter(tokens);
        if (filter != null) {
          properties.add(new GitFilterProperty(wildcard.getMatcher(), filter));
        }
      } catch (InvalidPatternException | PatternSyntaxException e) {
        log.warn("Found invalid git pattern: {}", line);
      }
    }
    return properties.toArray(new GitProperty[properties.size()]);
  }

  private static void processProperty(@NotNull List<GitProperty> properties, @NotNull Wildcard wildcard, @NotNull String property, @Nullable String value) {
    if (value == null) {
      return;
    }
    if (wildcard.isSvnCompatible()) {
      properties.add(new GitAutoProperty(wildcard.getMatcher(), property, value));
    }
    properties.add(new GitFileProperty(wildcard.getMatcher(), property, value));
  }

  @Nullable
  private String getMimeType(String[] tokens) {
    for (int i = 1; i < tokens.length; ++i) {
      String token = tokens[i];
      if (token.startsWith("binary")) {
        return SVNFileUtil.BINARY_MIME_TYPE;
      }
    }
    return null;
  }

  @Nullable
  private String getEol(String[] tokens) {
    for (int i = 1; i < tokens.length; ++i) {
      final String token = tokens[i];
      if (token.startsWith(EOL_PREFIX)) {
        switch (token.substring(EOL_PREFIX.length())) {
          case "lf":
            return SVNProperty.EOL_STYLE_LF;
          case "native":
            return SVNProperty.EOL_STYLE_NATIVE;
          case "cr":
            return SVNProperty.EOL_STYLE_CR;
          case "crlf":
            return SVNProperty.EOL_STYLE_CRLF;
        }
      }
    }
    return null;
  }

  @Nullable
  private String getFilter(String[] tokens) {
    for (int i = 1; i < tokens.length; ++i) {
      final String token = tokens[i];
      if (token.startsWith(FILTER_PREFIX)) {
        return token.substring(FILTER_PREFIX.length());
      }
    }
    return null;
  }
}