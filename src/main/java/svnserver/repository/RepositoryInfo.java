/*
 * This file is part of git-as-svn. It is subject to the license terms
 * in the LICENSE file found in the top-level directory of this distribution
 * and at http://www.gnu.org/licenses/gpl-2.0.html. No part of git-as-svn,
 * including this file, may be copied, modified, propagated, or distributed
 * except according to the terms contained in the LICENSE file.
 */
package svnserver.repository;

import org.jetbrains.annotations.NotNull;
import org.tmatesoft.svn.core.SVNURL;
import svnserver.repository.git.GitRepository;

/**
 * Mapped repository info.
 *
 * @author Artem V. Navrotskiy <bozaro@users.noreply.github.com>
 */
public final class RepositoryInfo {
  @NotNull
  private final SVNURL baseUrl;
  @NotNull
  private final GitRepository repository;

  public RepositoryInfo(@NotNull SVNURL baseUrl, @NotNull GitRepository repository) {
    this.baseUrl = baseUrl;
    this.repository = repository;
  }

  @NotNull
  public SVNURL getBaseUrl() {
    return baseUrl;
  }

  @NotNull
  public GitRepository getRepository() {
    return repository;
  }
}
