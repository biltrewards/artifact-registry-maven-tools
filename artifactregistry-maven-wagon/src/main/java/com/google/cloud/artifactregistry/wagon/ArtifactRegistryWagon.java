/*
 * Copyright 2019 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.cloud.artifactregistry.wagon;

import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpContent;
import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpRequestFactory;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.http.HttpResponse;
import com.google.api.client.http.HttpResponseException;
import com.google.api.client.http.HttpStatusCodes;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.auth.Credentials;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.http.HttpTransportFactory;
import com.google.cloud.artifactregistry.auth.CredentialProvider;
import com.google.cloud.artifactregistry.auth.DefaultCredentialProvider;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.Map;

import org.apache.maven.wagon.AbstractWagon;
import org.apache.maven.wagon.ConnectionException;
import org.apache.maven.wagon.ResourceDoesNotExistException;
import org.apache.maven.wagon.TransferFailedException;
import org.apache.maven.wagon.authentication.AuthenticationException;
import org.apache.maven.wagon.authorization.AuthorizationException;
import org.apache.maven.wagon.events.TransferEvent;
import org.apache.maven.wagon.repository.Repository;
import org.apache.maven.wagon.resource.Resource;


public final class ArtifactRegistryWagon extends AbstractWagon {

  private GoogleRepository googleRepository;
  private HttpRequestFactory requestFactory;
  private boolean hasCredentials;
  private HttpTransportFactory httpTransportFactory = NetHttpTransport::new;
  private CredentialProvider credentialProvider = DefaultCredentialProvider.getInstance();
  private Credentials credentials;
  private static final Map<String, String> sha1Cache = new ConcurrentHashMap<>();
  private static final ExecutorService jarPrefetchExecutor = new ThreadPoolExecutor(
      10, // core pool size
      50, // maximum pool size
      60L, TimeUnit.SECONDS, // keep alive time
      new LinkedBlockingQueue<>() // work queue
  );
  private static final String CACHE_DIR = "/tmp/biltarwagon/";

  private InputStream getInputStream(Resource resource)
      throws TransferFailedException, ResourceDoesNotExistException, AuthorizationException {
    try {
      GenericUrl url = googleRepository.constructURL(resource.getName());
      System.out.println(url);
      HttpRequest request = requestFactory.buildGetRequest(url);
      HttpResponse response = request.execute();
      return response.getContent();
    } catch (HttpResponseException e) {
      rethrowAuthorizationException(e);
      rethrowNotFoundException(e);
      throw new TransferFailedException("Received an error from the remote server.", e);
    } catch (IOException e) {
      throw new TransferFailedException("Failed to send request to remote server.", e);
    }
  }

  @Override
  protected void openConnectionInternal() throws ConnectionException, AuthenticationException {
    HttpTransport httpTransport = httpTransportFactory.create();
    try {
      credentials = credentialProvider.getCredential(new ProcessBuilderCommandExecutor());
      HttpRequestInitializer requestInitializer = new ArtifactRegistryRequestInitializer(credentials, this.getReadTimeout());
      requestFactory = httpTransport.createRequestFactory(requestInitializer);
      hasCredentials = true;
    } catch (IOException e) {
      requestFactory = httpTransport.createRequestFactory();
    }
    googleRepository = new GoogleRepository(repository);
  }

  @Override
  protected void closeConnection() throws ConnectionException {

  }

  @Override
  public void get(String resourceName, File destination)
      throws TransferFailedException, ResourceDoesNotExistException, AuthorizationException {
    getIfNewer(resourceName, destination, 0);
  }

  @Override
  public boolean resourceExists(String resource)
      throws TransferFailedException, AuthorizationException {
    try {
      GenericUrl url = googleRepository.constructURL(resource);
      HttpRequest request = requestFactory.buildHeadRequest(url);
      return request.execute().isSuccessStatusCode();
    } catch (HttpResponseException e) {
      if (e.getStatusCode() == HttpStatusCodes.STATUS_CODE_NOT_FOUND) {
        return false;
      }
      rethrowAuthorizationException(e);
      throw new TransferFailedException("Received an error from the remote server.", e);
    } catch (IOException e) {
      throw new TransferFailedException("Failed to send request to remote server.", e);
    }
  }

  @Override
  public boolean getIfNewer(String resourceName, File destination, long timestamp)
      throws TransferFailedException, ResourceDoesNotExistException, AuthorizationException {
    // Check if this is a request for a .sha1 file
    if (resourceName.endsWith(".sha1")) {
      String originalResource = resourceName.substring(0, resourceName.length() - 5); // Remove .sha1 suffix
      String cachedSha1 = sha1Cache.get(originalResource);
      if (cachedSha1 != null) {
        // Write the cached SHA1 to the destination file
        try (OutputStream out = new java.io.FileOutputStream(destination)) {
          out.write(cachedSha1.getBytes("UTF-8"));
          System.out.println("CACHE HIT SHA1: " + resourceName);
          return true;
        } catch (IOException e) {
          throw new TransferFailedException("Failed to write SHA1 to destination file.", e);
        }
      } else {
        System.out.println("CACHE MISS SHA1: " + resourceName);
      }
    }
    
    // Check if this is a .jar request and we have it cached
    if (resourceName.endsWith(".jar")) {
      File cachedJarFile = getCachedJarFile(resourceName);
      if (cachedJarFile.exists()) {
        try {
          Files.copy(cachedJarFile.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
          System.out.println("CACHE HIT JAR: " + resourceName);
          
          // Ensure SHA1 is cached for this jar
          if (!sha1Cache.containsKey(resourceName)) {
            String sha1Hash = computeSha1(destination);
            sha1Cache.put(resourceName, sha1Hash);
          }
          
          return true;
        } catch (IOException e) {
          System.err.println("Failed to copy cached jar file, falling back to remote fetch: " + e.getMessage());
          // Fall through to normal fetch
        }
      } else {
        System.out.println("CACHE MISS JAR: " + resourceName);
      }
    }
    
    // If this is a .pom file, prefetch the corresponding .jar file before downloading
    if (resourceName.endsWith(".pom")) {
      prefetchJarFile(resourceName);
    }
    
    Resource resource = new Resource(resourceName);
    this.fireGetInitiated(resource, destination);
    try {
      this.fireGetStarted(resource, destination);
      InputStream input = getInputStream(resource);
      this.getTransfer(resource, destination, input);
      
      // Compute and cache SHA1 for .pom and .jar files
      if (resourceName.endsWith(".pom") || resourceName.endsWith(".jar")) {
        String sha1Hash = computeSha1(destination);
        sha1Cache.put(resourceName, sha1Hash);
      }
      
      this.fireGetCompleted(resource, destination);
    } catch (Exception e) {
      this.fireTransferError(resource, e, TransferEvent.REQUEST_GET);
      throw e;
    }
    return true;
  }

  public void setHttpTransportFactory(HttpTransportFactory httpTransportFactory) {
    this.httpTransportFactory = httpTransportFactory;
  }

  public void setCredentialProvider(CredentialProvider provider) {
    this.credentialProvider = provider;
  }
  
  private String computeSha1(File file) throws TransferFailedException {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-1");
      try (FileInputStream fis = new FileInputStream(file)) {
        byte[] buffer = new byte[8192];
        int bytesRead;
        while ((bytesRead = fis.read(buffer)) != -1) {
          digest.update(buffer, 0, bytesRead);
        }
      }
      byte[] hashBytes = digest.digest();
      StringBuilder sb = new StringBuilder();
      for (byte b : hashBytes) {
        sb.append(String.format("%02x", b));
      }
      return sb.toString();
    } catch (NoSuchAlgorithmException | IOException e) {
      throw new TransferFailedException("Failed to compute SHA1 hash.", e);
    }
  }
  
  private void ensureCacheDirectory() {
    try {
      Path cacheDir = Paths.get(CACHE_DIR);
      if (!Files.exists(cacheDir)) {
        Files.createDirectories(cacheDir);
      }
    } catch (IOException e) {
      System.err.println("Failed to create cache directory: " + e.getMessage());
    }
  }
  
  private File getCachedJarFile(String jarResourceName) {
    // Replace path separators with underscores to create a flat file structure
    String fileName = jarResourceName.replace('/', '_').replace('\\', '_');
    return new File(CACHE_DIR + fileName);
  }
  
  private void prefetchJarFile(String pomResourceName) {
    // Convert .pom resource name to .jar resource name
    if (!pomResourceName.endsWith(".pom")) {
      return;
    }
    
    String jarResourceName = pomResourceName.substring(0, pomResourceName.length() - 4) + ".jar";
    File cachedJarFile = getCachedJarFile(jarResourceName);
    
    // Skip if already cached
    if (cachedJarFile.exists()) {
      return;
    }
    
    jarPrefetchExecutor.submit(() -> {
      try {
        ensureCacheDirectory();
        Resource jarResource = new Resource(jarResourceName);
        InputStream input = getInputStream(jarResource);
        
        // Create temporary file first, then move to final location atomically
        File tempFile = new File(cachedJarFile.getAbsolutePath() + ".tmp");
        try (OutputStream out = new java.io.FileOutputStream(tempFile)) {
          byte[] buffer = new byte[8192];
          int bytesRead;
          while ((bytesRead = input.read(buffer)) != -1) {
            out.write(buffer, 0, bytesRead);
          }
        }
        
        // Atomically move temp file to final location
        Files.move(tempFile.toPath(), cachedJarFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        
        // Compute and cache SHA1 for the prefetched jar
        String sha1Hash = computeSha1(cachedJarFile);
        sha1Cache.put(jarResourceName, sha1Hash);
        
        System.out.println("PREFETCHED JAR: " + jarResourceName);
        
      } catch (Exception e) {
        System.err.println("Failed to prefetch jar " + jarResourceName + ": " + e.getMessage());
        // Clean up any partial files
        File tempFile = new File(cachedJarFile.getAbsolutePath() + ".tmp");
        if (tempFile.exists()) {
          tempFile.delete();
        }
      }
    });
  }

  private void handlePutRequest(File source, Resource resource, GenericUrl url)
      throws AuthorizationException, ResourceDoesNotExistException, TransferFailedException {
    try {
      HttpRequest request = requestFactory.buildPutRequest(url, new HttpContent() {
        @Override
        public long getLength() throws IOException {
          return source.length();
        }

        @Override
        public String getType() {
          return null;
        }

        @Override
        public boolean retrySupported() {
          return true;
        }

        @Override
        public void writeTo(OutputStream out) throws IOException {
          try {
            putTransfer(resource, source, out, false);
          } catch (TransferFailedException | AuthorizationException | ResourceDoesNotExistException e) {
            throw new FileTransferException(e);
          }
        }
      });
      request.execute();
    } catch (HttpResponseException e) {
      rethrowAuthorizationException(e);
      rethrowNotFoundException(e);
      throw new TransferFailedException("Received an error from the remote server.", e);
    } catch (FileTransferException e) {
      Throwable cause = e.getCause();
      if (cause instanceof TransferFailedException) {
        throw (TransferFailedException) cause;
      } else if (cause instanceof AuthorizationException) {
        throw (AuthorizationException) cause;
      } else if (cause instanceof ResourceDoesNotExistException) {
        throw (ResourceDoesNotExistException) cause;
      }
      throw new TransferFailedException("Error uploading file.", cause);
    } catch (IOException e) {
      throw new TransferFailedException("Failed to send request to remote server.", e);
    }

  }

  @Override
  public void put(File source, String destination)
      throws TransferFailedException, ResourceDoesNotExistException, AuthorizationException {
    Resource resource = new Resource(destination);
    this.firePutInitiated(resource, source);
    resource.setContentLength(source.length());
    resource.setLastModified(source.lastModified());
    GenericUrl url = googleRepository.constructURL(resource.getName());
    this.firePutStarted(resource, source);
    handlePutRequest(source, resource, url);
    this.firePutCompleted(resource, source);
  }

  private void rethrowAuthorizationException(HttpResponseException e)
      throws AuthorizationException {
    if (e.getStatusCode() == HttpStatusCodes.STATUS_CODE_FORBIDDEN
        || e.getStatusCode() == HttpStatusCodes.STATUS_CODE_UNAUTHORIZED) {
      String errorMessage = "Permission denied on remote repository (or it may not exist). ";
      if (!hasCredentials) {
        errorMessage += "The request had no credentials because none were available "
            + "from the environment. Ensure that either 1) You are logged into gcloud or 2) "
            + "Application default credentials are setup (see "
            + "https://developers.google.com/accounts/docs/application-default-credentials for "
            + "more information).";
      }
      throw new AuthorizationException(errorMessage, e);
    }
  }

  private void rethrowNotFoundException(HttpResponseException e)
      throws ResourceDoesNotExistException {
    if (e.getStatusCode() == HttpStatusCodes.STATUS_CODE_NOT_FOUND) {
      throw new ResourceDoesNotExistException("The remote resource does not exist.", e);
    }
  }

  private static class FileTransferException extends IOException {

    FileTransferException(Throwable cause) {
      super(cause);
    }
  }

  private static class GoogleRepository {

    private final Repository repository;

    GoogleRepository(Repository repository) {
      this.repository = repository;
    }

    GenericUrl constructURL(String artifactPath) {
      if (artifactPath.startsWith("com/bilt/") || artifactPath.startsWith("com/biltrewards/") || artifactPath.startsWith("com/biltcard/")) {        
        GenericUrl url = new GenericUrl();
        url.setScheme("https");
        url.setHost(repository.getHost());
        url.appendRawPath("/single-scholar-280421/bilt-maven");
        url.appendRawPath("/");
        url.appendRawPath(artifactPath);
        return url;
      }

      GenericUrl url = new GenericUrl();
      url.setScheme("https");
      url.setHost("artifactregistry.googleapis.com");
      url.appendRawPath("/download/v1/projects/single-scholar-280421/locations/us/repositories/maven-central-cache/files/");
      try {
        url.appendRawPath(URLEncoder.encode(artifactPath, "UTF-8"));
      } catch (UnsupportedEncodingException e) {
        // UTF-8 is always supported, this should never happen
        throw new RuntimeException("UTF-8 encoding not supported", e);
      }
      url.appendRawPath(":download");
      url.set("alt", "media");
      return url;
    }
  }
}
