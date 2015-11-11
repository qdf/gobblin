package gobblin.config.configstore.impl;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.log4j.Logger;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import com.google.common.collect.Lists;
import com.google.common.io.Closer;

import gobblin.config.configstore.ConfigStore;
import gobblin.config.configstore.VersionComparator;
import gobblin.config.utils.PathUtils;


public class HdfsConfigStore implements ConfigStore {

  /**
   * 
   */
  private static final long serialVersionUID = 4048170056813280775L;

  private static final Logger LOG = Logger.getLogger(HdfsConfigStore.class);
  public static final String CONFIG_FILE_NAME = "main.conf";
  public static final String INCLUDE_FILE_NAME = "includes";

  private final String scheme;
  private final Path location;
  private final String currentVersion;
  private final FileSystem fs;
  private final VersionComparator<String> vc;
  private final Path currentVersionRoot;

  public HdfsConfigStore(String scheme, String location) {
    this(scheme, location, new SimpleVersionComparator());
  }

  public HdfsConfigStore(String scheme, String location, VersionComparator<String> vc) {
    this.scheme = scheme;
    this.location = new Path(location);
    try {
      this.fs = this.location.getFileSystem(new Configuration());
    } catch (IOException ioe) {
      LOG.error("can not initial the file system " + ioe.getMessage(), ioe);
      throw new RuntimeException(ioe);
    }

    this.vc = vc;
    this.currentVersion = this.findCurrentVersion();
    this.currentVersionRoot = new Path(this.location, this.currentVersion);
  }

  private String findCurrentVersion() {
    try {
      if (this.fs.isFile(this.location)) {
        throw new RuntimeException(String.format("location %s should be a directory ", this.location));
      }

      FileStatus[] fileStatus = this.fs.listStatus(this.location);
      if (fileStatus == null || fileStatus.length == 0) {
        throw new RuntimeException(String.format("location %s does not have any versions ", this.location));
      }

      List<String> versions = new ArrayList<String>();
      for (FileStatus f : fileStatus) {
        // versions should be directory
        if (!f.isDir()) {
          continue;
        }
        versions.add(f.getPath().getName());
      }
      String res = this.vc.getCurrentVersion(versions);

      if (res == null) {
        throw new RuntimeException(String.format("location %s does not have any valid version: ", this.location));
      }

      return res;
    } catch (IOException ioe) {
      LOG.error("can not find current version: " + ioe.getMessage(), ioe);
      throw new RuntimeException(ioe);
    }
  }

  @Override
  public String getScheme() {
    return this.scheme;
  }

  @Override
  public String getCurrentVersion() {
    return this.currentVersion;
  }

  @Override
  public URI getParent(URI uri) {
    if (uri == null || uri.toString().length() == 0)
      return null;

    Path self = new Path(this.currentVersionRoot, uri.toString());
    Path parent = self.getParent();

    try {
      return new URI(getRelativePath(parent));
    } catch (URISyntaxException e) {
      LOG.error(String.format("Got error when create URI for %s, exception %s", parent, e.getMessage()), e);
      throw new RuntimeException(e);
    }
  }

  @Override
  public Collection<URI> getChildren(URI uri) {
    if (uri == null)
      return null;
    Path self = new Path(this.currentVersionRoot, uri.toString());
    try {
      if (this.fs.isFile(self)) {
        return Collections.emptyList();
      }

      FileStatus[] fileStatus = this.fs.listStatus(self);
      if (fileStatus == null || fileStatus.length == 0) {
        return Collections.emptyList();
      }

      List<URI> res = new ArrayList<URI>();
      for (FileStatus f : fileStatus) {
        // valid node should be a directory
        if (!f.isDir()) {
          continue;
        }
        try {
          res.add(new URI(this.getRelativePath(f.getPath())));
        } catch (URISyntaxException e) {
          LOG.error(String.format("Got error when create URI for %s, exception %s", f.getPath(), e.getMessage()), e);
          throw new RuntimeException(e);
        }
      }

      return res;
    } catch (IOException ioe) {
      LOG.error(String.format("Got error when find children for %s, exception %s", self, ioe.getMessage()));
      throw new RuntimeException(ioe);
    }
  }

  @Override
  public Collection<URI> getImports(URI uri) {
    List<URI> result = new ArrayList<URI>();

    try {
      Path self = new Path(this.currentVersionRoot, uri.toString());
      Path includeFile = new Path(self, INCLUDE_FILE_NAME);
      List<String> imports = this.getImports(includeFile);
      for(String s: imports){
        try {
          result.add(new URI(s));
        }
        catch (URISyntaxException e) {
          LOG.error("Could not parse  " + s + " as URI", e);
        }
      }
      
      return result;
    }
    catch(IOException ioe){
      LOG.error("Could not find imports at path " + uri, ioe);
      return result;
    } 
  }

  private List<String> getImports(Path p) throws IOException{
    List<String> allImports = Lists.newArrayList();

    if(!fs.exists(p) || !fs.isFile(p)) {
      LOG.error("Include file " + p + " does not exist or is not a file.");
      return allImports;
    }

    Closer closer = Closer.create();

    try {
      FSDataInputStream fsDataInputStream = closer.register(fs.open(p));
      InputStreamReader inputStreamReader = closer.register(new InputStreamReader(fsDataInputStream));
      BufferedReader br = closer.register(new BufferedReader(inputStreamReader));
      String line = br.readLine();
      while (line != null) {
        Path singleImport = new Path(this.currentVersionRoot, line);
        if(this.fs.isDirectory(singleImport)){
          allImports.add(line);
        }
        else {
          LOG.error("Invalid imported for " + line);
        }

        line = br.readLine();
      }
    } catch(IOException exception) {
      LOG.error("Could not find imports at path " + p, exception);
      return Lists.newArrayList();
    } finally {
      closer.close();
    }

    return allImports;
  }

  @Override
  public Config getOwnConfig(URI uri) {
    if (uri == null)
      return ConfigFactory.empty();

    Closer closer = Closer.create();
    Path self = new Path(this.currentVersionRoot, uri.toString());
    Path configFile = new Path(self, CONFIG_FILE_NAME);
    try {
      if (!this.fs.isFile(configFile)) {
        return ConfigFactory.empty();
      }

      FSDataInputStream configFileStream = closer.register(this.fs.open(configFile));
      return ConfigFactory.parseReader(new InputStreamReader(configFileStream)).resolve();
    }
    catch (IOException ioe) {
      LOG.error(String.format("Got error when get own config for %s, exception %s", self, ioe.getMessage()), ioe);
      throw new RuntimeException(ioe);
    }
    finally {
      try {
        closer.close();
      } catch (IOException e) {
        LOG.error("Failed to close FsInputStream: " + e, e);
      }
    }
  }

  private String getRelativePath(Path p) {
    String root = PathUtils.getPathWithoutSchemeAndAuthority(this.currentVersionRoot).toString();
    String input = PathUtils.getPathWithoutSchemeAndAuthority(p).toString();


    if (input.equals(root)) {
      return "";
    }
    return input.substring(root.length()+1);
  }
}