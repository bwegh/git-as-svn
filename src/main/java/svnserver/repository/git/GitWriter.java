/*
 * This file is part of git-as-svn. It is subject to the license terms
 * in the LICENSE file found in the top-level directory of this distribution
 * and at http://www.gnu.org/licenses/gpl-2.0.html. No part of git-as-svn,
 * including this file, may be copied, modified, propagated, or distributed
 * except according to the terms contained in the LICENSE file.
 */
package svnserver.repository.git;

import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.tmatesoft.svn.core.SVNErrorCode;
import org.tmatesoft.svn.core.SVNErrorMessage;
import org.tmatesoft.svn.core.SVNException;
import org.tmatesoft.svn.core.SVNProperty;
import svnserver.Loggers;
import svnserver.ReferenceLink;
import svnserver.StringHelper;
import svnserver.auth.User;
import svnserver.repository.Depth;
import svnserver.repository.VcsConsumer;
import svnserver.repository.git.prop.PropertyMapping;
import svnserver.repository.git.push.GitPusher;
import svnserver.repository.locks.LockDesc;
import svnserver.repository.locks.LockStorage;

import java.io.IOException;
import java.util.*;

/**
 * Git commit writer.
 *
 * @author Artem V. Navrotskiy <bozaro@users.noreply.github.com>
 */
public final class GitWriter implements AutoCloseable {
    @NotNull
  public static final String keepFileName = ".keep";

  @NotNull
  public static final byte[] keepFileContents = GitRepository.emptyBytes;

  private static final int MAX_PROPERTY_ERRROS = 50;

  @NotNull
  private static final Logger log = Loggers.git;

  @NotNull
  private final GitBranch branch;
  @NotNull
  private final ObjectInserter inserter;
  @NotNull
  private final GitPusher pusher;
  @NotNull
  private final Object pushLock;
  @NotNull
  private final User user;

  GitWriter(@NotNull GitBranch branch, @NotNull GitPusher pusher, @NotNull Object pushLock, @NotNull User user) {
    this.branch = branch;
    this.pusher = pusher;
    this.pushLock = pushLock;
    this.inserter = branch.getRepository().getGit().newObjectInserter();
    this.user = user;
  }

  @NotNull
  public GitDeltaConsumer createFile(@NotNull GitEntry parent, @NotNull String name) throws IOException {
    return new GitDeltaConsumer(this, parent.createChild(name, false), null, user);
  }

  @NotNull
  public GitDeltaConsumer modifyFile(@NotNull GitEntry parent, @NotNull String name, @NotNull GitFile file) throws IOException {
    return new GitDeltaConsumer(this, parent.createChild(name, false), file, user);
  }

  @NotNull
  public GitWriter.GitCommitBuilder createCommitBuilder(@NotNull LockStorage lockManager, @NotNull Map<String, String> locks) throws IOException {
    return new GitCommitBuilder(lockManager, locks);
  }

  @NotNull
  public GitBranch getBranch() {
    return branch;
  }

  @NotNull ObjectInserter getInserter() {
    return inserter;
  }

  @Override
  public void close() {
    try (ObjectInserter unused = inserter) {
      // noop
    }
  }

  public final class GitCommitBuilder {
    @NotNull
    private final Deque<GitTreeUpdate> treeStack;
    @NotNull
    private final GitRevision revision;
    @NotNull
    private final LockStorage lockManager;
    @NotNull
    private final Map<String, String> locks;
    @NotNull
    private final List<VcsConsumer<CommitAction>> commitActions = new ArrayList<>();

    GitCommitBuilder(@NotNull LockStorage lockManager, @NotNull Map<String, String> locks) throws IOException {
      this.lockManager = lockManager;
      this.locks = locks;
      this.revision = branch.getLatestRevision();
      this.treeStack = new ArrayDeque<>();
      this.treeStack.push(new GitTreeUpdate("", getOriginalTree()));
    }

    private Iterable<GitTreeEntry> getOriginalTree() throws IOException {
      final RevCommit commit = revision.getGitNewCommit();
      if (commit == null) {
        return Collections.emptyList();
      }
      return branch.getRepository().loadTree(new GitTreeEntry(branch.getRepository().getGit(), FileMode.TREE, commit.getTree(), ""));
    }

    public void addDir(@NotNull String name, @Nullable GitFile sourceDir) throws SVNException, IOException {
      final GitTreeUpdate current = treeStack.element();
      if (current.getEntries().containsKey(name)) {
        throw new SVNException(SVNErrorMessage.create(SVNErrorCode.FS_ALREADY_EXISTS, getFullPath(name)));
      }
      commitActions.add(action -> action.openDir(name));
      treeStack.push(new GitTreeUpdate(name, branch.getRepository().loadTree(sourceDir == null ? null : sourceDir.getTreeEntry())));
    }

    @NotNull
    private String getFullPath(String name) {
      final StringBuilder fullPath = new StringBuilder();
      final Iterator<GitTreeUpdate> iter = treeStack.descendingIterator();
      while (iter.hasNext()) {
        fullPath.append(iter.next().getName()).append('/');
      }
      fullPath.append(name);
      return fullPath.toString();
    }

    public void openDir(@NotNull String name) throws SVNException, IOException {
      final GitTreeUpdate current = treeStack.element();
      final GitTreeEntry originalDir = current.getEntries().remove(name);
      if ((originalDir == null) || (!originalDir.getFileMode().equals(FileMode.TREE))) {
        throw new SVNException(SVNErrorMessage.create(SVNErrorCode.ENTRY_NOT_FOUND, getFullPath(name)));
      }
      commitActions.add(action -> action.openDir(name));
      treeStack.push(new GitTreeUpdate(name, branch.getRepository().loadTree(originalDir)));
    }

    public void checkDirProperties(@NotNull Map<String, String> props) {
      commitActions.add(action -> action.checkProperties(null, props, null));
    }

    public void closeDir() throws SVNException, IOException {
      final GitTreeUpdate last = treeStack.pop();
      final GitTreeUpdate current = treeStack.element();
      final String fullPath = getFullPath(last.getName());
      if (last.getEntries().isEmpty()) {
         final GitTreeEntry keepFile = new GitTreeEntry(
            branch.getRepository().getGit(),
            FileMode.REGULAR_FILE,
            inserter.insert(Constants.OBJ_BLOB, keepFileContents),
            keepFileName
        );
        last.getEntries().put(keepFile.getFileName(), keepFile);
      } else if(last.getEntries().containsKey(keepFileName) && last.getEntries().size() >= 2) {
        // remove keep file if it is not the only file in the directory
        // would be good to also validate the content
        last.getEntries().remove(keepFileName);
      }
      final ObjectId subtreeId = last.buildTree(inserter);
      log.debug("Create tree {} for dir: {}", subtreeId.name(), fullPath);
      if (current.getEntries().put(last.getName(), new GitTreeEntry(FileMode.TREE, new GitObject<>(branch.getRepository().getGit(), subtreeId), last.getName())) != null) {
        throw new SVNException(SVNErrorMessage.create(SVNErrorCode.FS_ALREADY_EXISTS, fullPath));
      }
      commitActions.add(CommitAction::closeDir);
    }

    public void saveFile(@NotNull String name, @NotNull GitDeltaConsumer deltaConsumer, boolean modify) throws SVNException, IOException {
      final GitDeltaConsumer gitDeltaConsumer = deltaConsumer;
      final GitTreeUpdate current = treeStack.element();
      final GitTreeEntry entry = current.getEntries().get(name);
      final GitObject<ObjectId> originalId = gitDeltaConsumer.getOriginalId();
      if (modify ^ (entry != null)) {
        throw new SVNException(SVNErrorMessage.create(SVNErrorCode.WC_NOT_UP_TO_DATE, "Working copy is not up-to-date: " + getFullPath(name)));
      }
      final GitObject<ObjectId> objectId = gitDeltaConsumer.getObjectId();
      if (objectId == null) {
        // Content not updated.
        if (originalId == null) {
          throw new SVNException(SVNErrorMessage.create(SVNErrorCode.INCOMPLETE_DATA, "Added file without content: " + getFullPath(name)));
        }
        return;
      }
      current.getEntries().put(name, new GitTreeEntry(getFileMode(gitDeltaConsumer.getProperties()), objectId, name));
      commitActions.add(action -> action.checkProperties(name, gitDeltaConsumer.getProperties(), gitDeltaConsumer));
    }

    private FileMode getFileMode(@NotNull Map<String, String> props) {
      if (props.containsKey(SVNProperty.SPECIAL)) return FileMode.SYMLINK;
      if (props.containsKey(SVNProperty.EXECUTABLE)) return FileMode.EXECUTABLE_FILE;
      return FileMode.REGULAR_FILE;
    }

    public void delete(@NotNull String name) throws SVNException {
      final GitTreeUpdate current = treeStack.element();
      final GitTreeEntry entry = current.getEntries().remove(name);
      if (entry == null) {
        throw new SVNException(SVNErrorMessage.create(SVNErrorCode.ENTRY_NOT_FOUND, getFullPath(name)));
      }
    }

    @Nullable
    public GitRevision commit(@NotNull User userInfo, @NotNull String message) throws SVNException, IOException {
      final GitTreeUpdate root = treeStack.element();
      ObjectId treeId = root.buildTree(inserter);
      log.debug("Create tree {} for commit.", treeId.name());

      final CommitBuilder commitBuilder = new CommitBuilder();
      final PersonIdent ident = createIdent(userInfo);
      commitBuilder.setAuthor(ident);
      commitBuilder.setCommitter(ident);
      commitBuilder.setMessage(message);
      final RevCommit parentCommit = revision.getGitNewCommit();
      if (parentCommit != null) {
        commitBuilder.setParentId(parentCommit.getId());
      }
      commitBuilder.setTreeId(treeId);
      final ObjectId commitId = inserter.insert(commitBuilder);
      inserter.flush();
      log.info("Create commit {}: {}", commitId.name(), StringHelper.getFirstLine(message));

      if (filterMigration(new RevWalk(branch.getRepository().getGit()).parseTree(treeId)) != 0) {
        log.info("Need recreate tree after filter migration.");
        return null;
      }

      synchronized (pushLock) {
        log.info("Validate properties");
        validateProperties(new RevWalk(branch.getRepository().getGit()).parseTree(treeId));

        log.info("Try to push commit in branch: {}", branch);
        if (!pusher.push(branch.getRepository().getGit(), commitId, branch.getGitBranch(), userInfo)) {
          log.info("Non fast forward push rejected");
          return null;
        }
        log.info("Commit is pushed");
        branch.updateRevisions();
        return branch.getRevision(commitId);
      }
    }

    private PersonIdent createIdent(User userInfo) {
      final String realName = userInfo.getRealName();
      final String email = userInfo.getEmail();
      return new PersonIdent(realName, email == null ? "" : email);
    }

    private int filterMigration(@NotNull RevTree tree) throws IOException, SVNException {
      final GitFile root = GitFileTreeEntry.create(branch, tree, 0);
      final GitFilterMigration validator = new GitFilterMigration(root);
      for (VcsConsumer<CommitAction> validateAction : commitActions) {
        validateAction.accept(validator);
      }
      return validator.done();
    }

    private void validateProperties(@NotNull RevTree tree) throws IOException, SVNException {
      final GitFile root = GitFileTreeEntry.create(branch, tree, 0);
      final GitPropertyValidator validator = new GitPropertyValidator(root);
      for (VcsConsumer<CommitAction> validateAction : commitActions) {
        validateAction.accept(validator);
      }
      validator.done();
    }

    public void checkUpToDate(@NotNull String path, int rev, boolean checkLock) throws SVNException, IOException {
      final GitFile file = revision.getFile(path);
      if (file == null) {
        throw new SVNException(SVNErrorMessage.create(SVNErrorCode.ENTRY_NOT_FOUND, path));
      } else if (file.getLastChange().getId() > rev) {
        throw new SVNException(SVNErrorMessage.create(SVNErrorCode.WC_NOT_UP_TO_DATE, "Working copy is not up-to-date: " + path));
      }
      if (checkLock) {
        checkLockFile(file);
      }
    }

    private void checkLockFile(@NotNull GitFile file) throws SVNException, IOException {
      final String fullPath = file.getFullPath();
      final Iterator<LockDesc> iter = lockManager.getLocks(user, branch, fullPath, Depth.Infinity);
      while (iter.hasNext())
        checkLockDesc(iter.next());
    }

    private void checkLockDesc(@Nullable LockDesc lockDesc) throws SVNException {
      if (lockDesc != null) {
        final String token = locks.get(lockDesc.getPath());
        if (!lockDesc.getToken().equals(token))
          throw new SVNException(SVNErrorMessage.create(SVNErrorCode.FS_BAD_LOCK_TOKEN, lockDesc.getPath()));
      }
    }
  }

  private abstract class CommitAction {
    @NotNull
    private final Deque<GitFile> treeStack;

    CommitAction(@NotNull GitFile root) {
      this.treeStack = new ArrayDeque<>();
      this.treeStack.push(root);
    }

    @NotNull
    GitFile getElement() {
      return treeStack.element();
    }

    final void openDir(@NotNull String name) throws IOException {
      final GitFile file = treeStack.element().getEntry(name);
      if (file == null) {
        throw new IllegalStateException("Invalid state: can't find file " + name + " in created commit.");
      }
      treeStack.push(file);
    }

    public abstract void checkProperties(@Nullable String name, @NotNull Map<String, String> props, @Nullable GitDeltaConsumer deltaConsumer) throws IOException;

    public final void closeDir() {
      treeStack.pop();
    }
  }

  private class GitFilterMigration extends CommitAction {
    private int migrateCount = 0;

    GitFilterMigration(@NotNull GitFile root) {
      super(root);
    }

    @Override
    public void checkProperties(@Nullable String name, @NotNull Map<String, String> props, @Nullable GitDeltaConsumer deltaConsumer) throws IOException {
      final GitFile dir = getElement();
      final GitFile node = name == null ? dir : dir.getEntry(name);
      if (node == null) {
        throw new IllegalStateException("Invalid state: can't find entry " + name + " in created commit.");
      }

      if (deltaConsumer != null) {
        assert (node.getFilter() != null);
        if (deltaConsumer.migrateFilter(node.getFilter())) {
          migrateCount++;
        }
      }
    }

    int done() {
      return migrateCount;
    }
  }

  private class GitPropertyValidator extends CommitAction {
    @NotNull
    private final Map<String, Set<String>> propertyMismatch = new TreeMap<>();
    private int errorCount = 0;

    GitPropertyValidator(@NotNull GitFile root) {
      super(root);
    }

    @Override
    public void checkProperties(@Nullable String name, @NotNull Map<String, String> props, @Nullable GitDeltaConsumer deltaConsumer) throws IOException {
      final GitFile dir = getElement();
      final GitFile node = name == null ? dir : dir.getEntry(name);
      if (node == null) {
        throw new IllegalStateException("Invalid state: can't find entry " + name + " in created commit.");
      }

      if (deltaConsumer != null) {
        assert (node.getFilter() != null);
        if (!node.getFilter().getName().equals(deltaConsumer.getFilterName())) {
          throw new IllegalStateException("Invalid writer filter:\n"
              + "Expected: " + node.getFilter().getName() + "\n"
              + "Actual: " + deltaConsumer.getFilterName());
        }
      }

      final Map<String, String> expected = node.getProperties();
      if (!props.equals(expected)) {
        if (errorCount < MAX_PROPERTY_ERRROS) {
          final StringBuilder delta = new StringBuilder();
          delta.append("Expected:\n");
          for (Map.Entry<String, String> entry : expected.entrySet()) {
            delta.append("  ").append(entry.getKey()).append(" = \"").append(entry.getValue()).append("\"\n");
          }
          delta.append("Actual:\n");
          for (Map.Entry<String, String> entry : props.entrySet()) {
            delta.append("  ").append(entry.getKey()).append(" = \"").append(entry.getValue()).append("\"\n");
          }
          propertyMismatch.compute(delta.toString(), (key, value) -> {
            if (value == null) {
              value = new TreeSet<>();
            }
            value.add(node.getFullPath());
            return value;
          });
          errorCount++;
        }
      }
    }

    void done() throws SVNException {
      if (!propertyMismatch.isEmpty()) {
        final StringBuilder message = new StringBuilder();
        for (Map.Entry<String, Set<String>> entry : propertyMismatch.entrySet()) {
          if (message.length() > 0) {
            message.append("\n");
          }
          message.append("Invalid svn properties on files:\n");
          for (String path : entry.getValue()) {
            message.append("  ").append(path).append("\n");
          }
          message.append(entry.getKey());
        }
        message.append("\n"
            + "----------------\n" +
            "Subversion properties must be consistent with Git config files:\n");
        for (String configFile : PropertyMapping.getRegisteredFiles()) {
          message.append("  ").append(configFile).append('\n');
        }
        message.append("\n" +
            "For more detailed information, see:").append("\n").append(ReferenceLink.InvalidSvnProps.getLink());
        throw new SVNException(SVNErrorMessage.create(SVNErrorCode.REPOS_HOOK_FAILURE, message.toString()));
      }
    }
  }
}
