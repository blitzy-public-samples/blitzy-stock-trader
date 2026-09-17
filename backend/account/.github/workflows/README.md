# This folder contains GitHub Actions workflows

Workflows are used to build, scan, and deploy this service.

This file describes the workflows that are used to compile the app, build the Docker image, scan for vulnerabilities and code quality, and publish it to a container registry (such as Azure Container Registry or AWS ECR).

Workflows are defined in the respective YAML files in this directory (e.g., `build-test-push-azure-acr.yml`, `build-test-push-aws-ecr.yml`, `sonarcloud.yml`).

## Code Scanning and Quality Workflows

### Trivy Code Scanning (in Azure ACR Workflow)
The `build-test-push-azure-acr.yml` workflow includes a step to scan your code and Docker images for vulnerabilities using [Trivy](https://github.com/aquasecurity/trivy). This helps ensure your application and its dependencies are free from known security issues. The Trivy scan runs after the Docker image is built and before it is pushed to Azure Container Registry.

### SonarCloud Code Quality
A workflow named `sonarcloud.yml` is included to analyze code quality and maintainability using [SonarCloud](https://sonarcloud.io/). This workflow runs static analysis and reports issues such as bugs, code smells, and security vulnerabilities.

#### Required Changes for SonarCloud
To enable SonarCloud analysis, you must add the following properties to your `pom.xml`:
```
<sonar.organization>ibmstocktrader</sonar.organization>
<sonar.host.url>https://sonarcloud.io</sonar.host.url>
```
Make sure to configure your SonarCloud project and set up any required secrets (such as `SONAR_TOKEN`) in your repository settings.

## Copying Workflows
Copy these workflows to other microservices and change the following settings in the `env` section of the workflow file:
```
  # EDIT secrets with your registry, registry path, and credentials
  REGISTRY_NAME: <your-registry-name>
  IMAGE_NAME: <your-image-name>
  APP_NAME: <your-app-name>
  GITOPS_REPO: <your-gitops-repo>
  GITOPS_DIR: <your-gitops-dir>
  GITOPS_USERNAME: ${{ secrets.GITOPS_USERNAME }}
  GITOPS_TOKEN: ${{ secrets.GITOPS_TOKEN }}
```

GitOps registry is where the deployment manifest or custom resource file is stored.
The workflow updates that file with the new image location and tag.

Additionally, you need to configure the following secrets in your application git repo:
```
REGISTRY_LOGIN_SERVER - Your registry login server (e.g., <registry-name>.azurecr.io or <aws-account-id>.dkr.ecr.<region>.amazonaws.com)
REGISTRY_USERNAME - Username for your registry (if required)
REGISTRY_PASSWORD - Password or token for your registry (if required)
GITOPS_TOKEN - GitHub PAT with write access to your GitOps repo
GITOPS_USERNAME - Your GitHub username
SONAR_TOKEN - SonarCloud authentication token (for SonarCloud workflow)
```

## Workflow Overview
These workflows typically:
- Build the application (e.g., using Maven or Gradle)
- Build and push the Docker image to the container registry
- Scan the code and images for vulnerabilities (Trivy, in Azure ACR workflow)
- Analyze code quality and security (SonarCloud)
- Update the GitOps repository with the new image tag for deployment

### Required Setup
1. Create a container registry (Azure, AWS, or other)
2. Set up your deployment environment (e.g., AKS, EKS, or other Kubernetes cluster)
3. Fork or create a GitOps repo for deployment manifests
4. (Optional) Set up SonarCloud project and repository secrets

### Environment Variables
The workflows use these environment variables:
```
REGISTRY_NAME - Your container registry name
GITOPS_REPO - Your GitOps repository (format: username/repo)
GITOPS_DIR - Directory containing deployment manifests
IMAGE_NAME - Name of your Docker image
APP_NAME - Name of your application
IMAGE_TAG - Image tag (defaults to GitHub commit SHA)
```

## Disable other workflows
If this repo contains other workflows you do not need, you can disable or remove them.
To disable a workflow, go to `Actions`, select the workflow, click the `...` menu, and click `Disable workflow`.
You can re-enable the workflow later in the same way. 