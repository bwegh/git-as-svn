/*
 * This file is part of git-as-svn. It is subject to the license terms
 * in the LICENSE file found in the top-level directory of this distribution
 * and at http://www.gnu.org/licenses/gpl-2.0.html. No part of git-as-svn,
 * including this file, may be copied, modified, propagated, or distributed
 * except according to the terms contained in the LICENSE file.
 */
package svnserver.ext.gitlfs.filter;

import org.apache.commons.io.IOUtils;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.ObjectStream;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.bozaro.gitlfs.common.data.Meta;
import ru.bozaro.gitlfs.pointer.Constants;
import ru.bozaro.gitlfs.pointer.Pointer;
import svnserver.auth.User;
import svnserver.context.LocalContext;
import svnserver.ext.gitlfs.server.LfsServer;
import svnserver.ext.gitlfs.server.LfsServerEntry;
import svnserver.ext.gitlfs.storage.LfsReader;
import svnserver.ext.gitlfs.storage.LfsStorage;
import svnserver.ext.gitlfs.storage.LfsWriter;
import svnserver.repository.SvnForbiddenException;
import svnserver.repository.git.GitObject;
import svnserver.repository.git.filter.GitFilter;
import svnserver.repository.git.filter.GitFilterHelper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;

/**
 * Filter for Git LFS.
 *
 * @author Artem V. Navrotskiy <bozaro@users.noreply.github.com>
 * @author Marat Radchenko <marat@slonopotamus.org>
 */
public final class LfsFilter implements GitFilter {

  @Nullable
  private final LfsStorage storage;
  @NotNull
  private final Map<String, String> cacheMd5;

  public LfsFilter(@NotNull LocalContext context, @Nullable LfsStorage lfsStorage) {
    this.storage = lfsStorage;
    this.cacheMd5 = GitFilterHelper.getCacheMd5(this, context.getShared().getCacheDB());
    final LfsServer lfsServer = context.getShared().get(LfsServer.class);
    if (storage != null && lfsServer != null) {
      context.add(LfsServerEntry.class, new LfsServerEntry(lfsServer, context, storage));
    }
  }

  @NotNull
  @Override
  public String getName() {
    return "lfs";
  }

  @NotNull
  @Override
  public String getMd5(@NotNull GitObject<? extends ObjectId> objectId) throws IOException {
    final ObjectLoader loader = objectId.openObject();

    try (ObjectStream stream = loader.openStream()) {
      final Meta meta = parseMeta(stream);
      if (meta != null) {
        final String md5 = getReader(meta).getMd5();
        if (md5 != null)
          return md5;
      }
    }

    return GitFilterHelper.getMd5(this, cacheMd5, null, objectId);
  }

  @Override
  public long getSize(@NotNull GitObject<? extends ObjectId> objectId) throws IOException {
    final ObjectLoader loader = objectId.openObject();

    try (ObjectStream stream = loader.openStream()) {
      final Meta meta = parseMeta(stream);
      if (meta != null)
        return meta.getSize();
    }

    return loader.getSize();
  }

  @NotNull
  @Override
  public InputStream inputStream(@NotNull GitObject<? extends ObjectId> objectId) throws IOException {
    final ObjectLoader loader = objectId.openObject();
    try (ObjectStream stream = loader.openStream()) {
      final byte[] header = new byte[Constants.POINTER_MAX_SIZE];
      int length = IOUtils.read(stream, header, 0, header.length);
      if (length < header.length) {
        final Meta meta = parseMeta(header, length);
        if (meta != null)
          return getReader(meta).openStream();
      }

      // We need to re-open stream
      return loader.openStream();
    }
  }

  @NotNull
  @Override
  public OutputStream outputStream(@NotNull OutputStream stream, @NotNull User user) throws IOException {
    return new TemporaryOutputStream(getStorage().getWriter(user), stream);
  }

  @Nullable
  private static Meta parseMeta(@NotNull InputStream stream) throws IOException {
    final byte[] header = new byte[Constants.POINTER_MAX_SIZE];
    int length = IOUtils.read(stream, header, 0, header.length);
    if (length >= header.length)
      return null;

    return parseMeta(header, length);
  }

  @NotNull
  private LfsReader getReader(@NotNull Meta meta) throws IOException {
    final LfsReader reader = getStorage().getReader(meta.getOid(), meta.getSize());
    if (reader == null)
      throw new SvnForbiddenException();

    return reader;
  }

  @Nullable
  private static Meta parseMeta(@NotNull byte[] header, int length) {
    final Map<String, String> pointer = Pointer.parsePointer(header, 0, length);
    if (pointer == null)
      return null;

    final String oid = pointer.get(Constants.OID);
    final long size = Long.parseLong(pointer.get(Constants.SIZE));
    return new Meta(oid, size);
  }

  @NotNull
  private LfsStorage getStorage() {
    if (storage == null)
      throw new IllegalStateException("LFS is not configured");

    return storage;
  }

  private static class TemporaryOutputStream extends OutputStream {
    @NotNull
    private final LfsWriter dataStream;
    @NotNull
    private final OutputStream pointerStream;
    private long size;

    private TemporaryOutputStream(@NotNull LfsWriter dataStream, @NotNull OutputStream pointerStream) {
      this.dataStream = dataStream;
      this.pointerStream = pointerStream;
      size = 0;
    }

    @Override
    public void write(int b) throws IOException {
      dataStream.write(b);
      size++;
    }

    @Override
    public void write(@NotNull byte[] b, int off, int len) throws IOException {
      dataStream.write(b, off, len);
      size += len;
    }

    @Override
    public void flush() throws IOException {
      dataStream.flush();
    }

    @Override
    public void close() throws IOException {
      try (OutputStream pointerOut = pointerStream) {
        final Map<String, String> pointer;

        try (LfsWriter dataOut = dataStream) {
          pointer = size <= 0 ? null : Pointer.createPointer(dataOut.finish(null), size);
        }

        if (pointer != null)
          pointerOut.write(Pointer.serializePointer(pointer));
      }
    }
  }
}
