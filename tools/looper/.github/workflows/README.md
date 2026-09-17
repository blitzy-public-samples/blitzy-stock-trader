# This folder contains GitHub Actions workflows

Workflows are used to build and deploy the service.

This file describes the workflows that are used to compile the app, build the Docker image, scan it for vulnerabilities, and publish it to a container registry.

Workflows are defined in the following files:
- [build-test-push-azure-acr.yml](build-test-push-azure-acr.yml)
- [build-test-push-aws-ecr.yml](build-test-push-aws-ecr.yml)

Copy these workflows to other microservices and change the following settings in the `env` section of the workflow file:
```
  # EDIT secrets with your registry, registry path, and credentials
  ACR_NAME: <your-acr-name>
  AWS_ACCOUNT_ID: <your-aws-account-id>
  IMAGE_NAME: <your-image-name>
  APP_NAME: <your-app-name>
  GITOPS_REPO: <your-gitops-repo>
  GITOPS_DIR: application
  GITOPS_USERNAME: ${{ secrets.GITOPS_USERNAME }}
  GITOPS_TOKEN: ${{ secrets.GITOPS_TOKEN }}
```

GitOps registry is where the deployment manifest or custom resource file is stored.
The workflow updates that file with the new image location and tag.

Additionally, you need to configure the following secrets in your application git repo:
```
AZURE_CLIENT_ID - Azure App Registration or Managed Identity client ID
AZURE_TENANT_ID - Azure tenant ID
AZURE_SUBSCRIPTION_ID - Azure subscription ID
AWS_ACCESS_KEY_ID - AWS access key ID
AWS_SECRET_ACCESS_KEY - AWS secret access key
GITOPS_TOKEN - GitHub PAT with write access to your GitOps repo
GITOPS_USERNAME - Your GitHub username
ACR_LOGIN_SERVER - Your ACR login server (e.g., <acr-name>.azurecr.io)
AWS_REGION - AWS region (e.g., us-east-1)
```

## Workflow Overview
These workflows:
- Build the application (e.g., using Maven)
- Scan the built Docker image for vulnerabilities using Trivy before pushing
- Push the Docker image to a container registry (Azure ACR or AWS ECR)
- Update the GitOps repository with the new image tag for deployment

### Required Setup
1. Create a container registry (Azure ACR or AWS ECR)
2. Set up your Kubernetes cluster and GitOps repo for deployment manifests

### Environment Variables
The workflows use these environment variables:
```
ACR_NAME - Your Azure Container Registry name
AWS_ACCOUNT_ID - Your AWS account ID
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