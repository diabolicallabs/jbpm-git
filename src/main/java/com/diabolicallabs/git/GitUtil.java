package com.diabolicallabs.git;

import net.jodah.failsafe.Failsafe;
import net.jodah.failsafe.RetryPolicy;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.http.HttpHeaders;
import org.apache.http.HttpResponse;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.methods.RequestBuilder;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.HttpClientBuilder;
import org.drools.compiler.compiler.DecisionTableProvider;
import org.drools.decisiontable.DecisionTableProviderImpl;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.PullResult;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.TransportException;
import org.eclipse.jgit.errors.RemoteRepositoryException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.json.JSONObject;
import org.kie.api.KieServices;
import org.kie.api.builder.KieFileSystem;
import org.kie.api.io.Resource;
import org.kie.internal.builder.DecisionTableConfiguration;
import org.kie.internal.builder.DecisionTableInputType;
import org.kie.internal.builder.KnowledgeBuilderFactory;
import org.kie.internal.io.ResourceFactory;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;

public class GitUtil {

  private static final String DECISION_CENTRAL_HOST = "http://localhost:8080";
  private static final String GIT_PROJECT_URL = "https://gitlab.diabolicallabs.com/labs/dmpoc.git";
  private static final CredentialsProvider PROVIDER = new BasicCredentialsProvider();
  private static final UsernamePasswordCredentials CREDENTIALS = new UsernamePasswordCredentials("kawika", "pulamakeokeo1!");

  static {
    PROVIDER.setCredentials(AuthScope.ANY, CREDENTIALS);
  }

  private static final HttpClient HTTP_CLIENT = HttpClientBuilder.create().setDefaultCredentialsProvider(PROVIDER).build();

  private static class NotFinishedException extends RuntimeException {

  }

  private void synchronize() throws IOException, GitAPIException, URISyntaxException {

    System.out.println("Synchronizing Decision Central from GitLab");
    File repositoryDirectory = java.nio.file.Files.createTempDirectory("squid").toFile();
    System.out.println("Cloning repo from Decision Central");
    Git git = Git.cloneRepository()
      .setURI(DECISION_CENTRAL_HOST + "/business-central/git/ROME/example-Mortgages")
      .setCredentialsProvider(new UsernamePasswordCredentialsProvider("kawika", "pulamakeokeo1!"))
      .setDirectory(repositoryDirectory)
      .call();

    System.out.println("Adding origin as Decision Central");
    git.remoteAdd()
      .setName("origin")
      .setUri(new URIish(DECISION_CENTRAL_HOST + "/business-central/git/ROME/example-Mortgages"))
      .call();

    System.out.println("Adding upstream as GitLab");
    git.remoteAdd()
      .setName("upstream")
      .setUri(new URIish(GIT_PROJECT_URL))
      .call();

    System.out.println("Pulling latest changes from GitLab");
    PullResult pullResult = git.pull()
      .setRemote("upstream")
      .setRemoteBranchName("master")
      .setCredentialsProvider(new UsernamePasswordCredentialsProvider("kawika", "pulamakeokeo1!"))
      .call();
    System.out.println("Pull result: " + pullResult.toString());
    assert pullResult.isSuccessful();

    System.out.println("Pushing latest changes to Decision Central");
    git.push()
      .setRemote("origin")
      .setCredentialsProvider(new UsernamePasswordCredentialsProvider("kawika", "pulamakeokeo1!"))
      .call();
    FileUtils.deleteDirectory(repositoryDirectory);
    System.out.println("Deleted local temporary repository");
  }

  private void compile() throws GitAPIException, IOException {

    System.out.println("About to compile rules from generated decision table");
    KieServices kieServices = KieServices.Factory.get();
    Resource dt = ResourceFactory.newClassPathResource("Discount.xlsx", this.getClass());

    DecisionTableProvider decisionTableProvider = new DecisionTableProviderImpl();
    DecisionTableConfiguration decisionTableConfiguration = KnowledgeBuilderFactory.newDecisionTableConfiguration();
    decisionTableConfiguration.setInputType(DecisionTableInputType.XLS);
    decisionTableConfiguration.setWorksheetName("Sheet1");
    String drl = decisionTableProvider.loadFromResource(dt, decisionTableConfiguration);
    System.out.println("Generated rules: " + drl);
    KieFileSystem kieFileSystem = kieServices.newKieFileSystem().write(dt);

    File repositoryDirectory = java.nio.file.Files.createTempDirectory("squid").toFile();
    System.out.println("Cloning GitLab repository");
    Git git = Git.cloneRepository()
      .setURI(GIT_PROJECT_URL)
      .setCredentialsProvider(new UsernamePasswordCredentialsProvider("kawika", "pulamakeokeo1!"))
      .setDirectory(repositoryDirectory)
      .call();

    Repository repository = git.getRepository();

    String fileName = "src/docs/Discount.xlsx";
    File originalSpreadsheet = new File("src/main/resources/Discount.xlsx");
    File copiedSpreadsheet = new File(repositoryDirectory, fileName);
    FileUtils.copyFile(originalSpreadsheet, copiedSpreadsheet);
    System.out.println("Copied spreadsheet to local repository");
    git.add()
      .addFilepattern(fileName)
      .call();
    System.out.println("Added spreadsheet to staging");

    fileName = "src/main/resources/ROME.drl";
    File drlFile = new File(repositoryDirectory, fileName);
    FileUtils.writeStringToFile(drlFile, drl, "utf-8");
    System.out.println("Copied changed rules to local repository");
    git.add()
      .addFilepattern(fileName)
      .call();
    System.out.println("Added rules to staging");
    git.commit()
      .setMessage("Shadow Man is the GOAT")
      .call();
    System.out.println("Committed rules to local repository");
    git.push()
      .setCredentialsProvider(new UsernamePasswordCredentialsProvider("kawika", "pulamakeokeo1!"))
      .call();
    System.out.println("Pushed generated rules to GitLab");

    FileUtils.deleteDirectory(repositoryDirectory);
  }

  private void build() throws IOException, InterruptedException {

    HttpUriRequest request = RequestBuilder.post()
      .setUri(DECISION_CENTRAL_HOST + "/business-central/rest/spaces/ROME/projects/ROME/maven/deploy")
      .setHeader(HttpHeaders.CONTENT_TYPE, "application/json")
      .setHeader(HttpHeaders.ACCEPT, "application/json")
      .build();

    HttpResponse response = HTTP_CLIENT.execute(request);
    System.out.println(response.getStatusLine().getStatusCode());
    System.out.println(response.getStatusLine().getReasonPhrase());
    String result = IOUtils.toString(response.getEntity().getContent(), StandardCharsets.UTF_8.name());

    JSONObject jsonResult = new JSONObject(result);
    String jobId = jsonResult.getString("jobId");
    String status = jsonResult.getString("status");
    String projectName = jsonResult.getString("projectName");
    String spaceName = jsonResult.getString("spaceName");
    System.out.println("Job Id: " + jobId);
    System.out.println("Status: " + status);

    int maxTries = 10;
    JSONObject deploymentResult = this.checkJob(jobId);
    while (!deploymentResult.getString("status").equals("SUCCESS")) {
      maxTries--;
      if (maxTries == 0) break;
      TimeUnit.SECONDS.sleep(5);
      deploymentResult = this.checkJob(jobId);
      if (deploymentResult.get("status").equals("FAIL")) break;
    }
    System.out.println("Deployment response: " + deploymentResult.toString());
  }

  private JSONObject checkJob(String jobId) throws IOException {

    System.out.println("About to check " + jobId);
    HttpUriRequest request = RequestBuilder.get()
      .setUri(DECISION_CENTRAL_HOST + "/business-central/rest/jobs/" + jobId)
      .setHeader(HttpHeaders.ACCEPT, "application/json")
      .build();

    HttpResponse response = HTTP_CLIENT.execute(request);
    String result = IOUtils.toString(response.getEntity().getContent(), StandardCharsets.UTF_8.name());
    System.out.println("Job result " + result);
    return new JSONObject(result);
  }

  private void createContainer() throws IOException {
// mortgages_1.0.0-SNAPSHOT
    String containerId = "ROME_" + LocalDateTime.now().format(DateTimeFormatter.ISO_DATE_TIME);
    JSONObject jsonRequest = new JSONObject()
      .put("container-id", containerId)
      .put("container-name", "rome")
      .put("status", "STARTED")
      .put("release-id", new JSONObject()
        .put("group-id", "com.bcbsfl.rome")
        .put("artifact-id", "rome-rules")
        .put("version", "1.0.0-SNAPSHOT")
      );

    System.out.println("Create container request: " + jsonRequest.toString());

    HttpUriRequest request = RequestBuilder.put()
      .setUri(DECISION_CENTRAL_HOST + "/business-central/rest/controller/management/servers/dev/containers/" + containerId)
      .setHeader(HttpHeaders.CONTENT_TYPE, "application/json")
      .setHeader(HttpHeaders.ACCEPT, "application/json")
      .setEntity(new StringEntity(jsonRequest.toString(), ContentType.APPLICATION_JSON))
      .build();

    HttpResponse response = HTTP_CLIENT.execute(request);
    System.out.println("Create container response: " + response.getStatusLine());
    assert response.getStatusLine().getStatusCode() == 201;
  }

  public static void main(String[] args) {

    RetryPolicy retryPolicy =  new RetryPolicy<Void>()
      .withDelay(Duration.ofSeconds(1))
      .withMaxRetries(3)
      .handle(RemoteRepositoryException.class)
      .handle(TransportException.class);

    GitUtil gitUtil = new GitUtil();
    try {
      /*
        Clone repo
        Switch to development branch
        Set GAV to datetime
        Compile
        Push to Gitlab
        Synchronize with DC
        Build in DC
        Create container for user
       */
      gitUtil.compile();
      Failsafe.with(retryPolicy).run(gitUtil::synchronize);
      gitUtil.build();
      gitUtil.createContainer();
    } catch (Exception e) {
      e.printStackTrace();
    }
  }
}
