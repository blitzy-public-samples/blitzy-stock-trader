# Portfolio Assistant
### Stock Trader AI Helper

Manage your portfolio with the help of AI. This application is part of the Stock Trader solution and provides insights and recommendations based on your stock portfolio.

This requires the Portfolio and Stock Quote microservices to be running. Additional microservices may be required as development continues.

# Prerequisites 
## Use auto mode to create EKS Cluster

Use AWS Console UI to create cluster with automode turned on

## Create Storage Class
```yaml
apiVersion: storage.k8s.io/v1
kind: StorageClass
metadata:
  name: auto-ebs-sc
  annotations:
    storageclass.kubernetes.io/is-default-class: "true"
provisioner: ebs.csi.eks.amazonaws.com
volumeBindingMode: WaitForFirstConsumer
parameters:
  type: gp3
  encrypted: "true"
```

```bash
kubectl appply -f storageclass.yml
```
## Create GPU NodePool

From: [Deploy an accelerated workload](https://docs.aws.amazon.com/eks/latest/userguide/auto-accelerated.html) but altered slightly to reduce the amount of time we’re paying for a node that unutilized.

```yaml
apiVersion: karpenter.sh/v1
kind: NodePool
metadata:
  name: gpu
spec:
  disruption:
    budgets:
    # - nodes: 10%
    consolidateAfter: 5m
    consolidationPolicy: WhenEmpty
  template:
    metadata: {}
    spec:
      nodeClassRef:
        group: eks.amazonaws.com
        kind: NodeClass
        name: default
      requirements:
        - key: "karpenter.sh/capacity-type"
          operator: In
          values: ["on-demand"]
        - key: "kubernetes.io/arch"
          operator: In
          values: ["amd64"]
        - key: "eks.amazonaws.com/instance-family"
          operator: In
          values:
          - g6e
          - g6
      taints:
        - key: nvidia.com/gpu
          effect: NoSchedule
      terminationGracePeriod: 0h5m0s
```


## Deploy Ollama Helm chart


First, let’s try the generic install

```bash
helm repo add otwld https://helm.otwld.com/
helm repo update

helm install ollama otwld/ollama \
  --namespace ollama --create-namespace \
  -f ollama-crd.yaml

NAME: ollama
LAST DEPLOYED: Mon Aug  4 16:34:24 2025
NAMESPACE: ollama
STATUS: deployed
REVISION: 1
NOTES:
1. Get the application URL by running these commands:
  export POD_NAME=$(kubectl get pods --namespace ollama -l "app.kubernetes.io/name=ollama,app.kubernetes.io/instance=ollama" -o jsonpath="{.items[0].metadata.name}")
  export CONTAINER_PORT=$(kubectl get pod --namespace ollama $POD_NAME -o jsonpath="{.spec.containers[0].ports[0].containerPort}")
  echo "Visit http://127.0.0.1:8080 to use your application"
  kubectl --namespace ollama port-forward $POD_NAME 8080:$CONTAINER_PORT

```

*Note:* t takes a while for the GPU instance to deploy

### Deploy a specific deployment with persistent model storage
```yaml
ollama:
  gpu:
    enabled: true
    type: "nvidia"
    number: 1
  models:
    pull:
    - llama3.2
resources:
  limits:
    nvidia.com/gpu: "1"
  requests:
    nvidia.com/gpu: "1"

persistentVolume:
  enabled: true
  accessModes:
    - "ReadWriteOnce"
  size: "60Gi"
  storageClass: "auto-ebs-sc"

nodeSelector:
  eks.amazonaws.com/instance-gpu-name: l40s
  eks.amazonaws.com/compute-type: auto

tolerations:
  - key: "nvidia.com/gpu"
    operator: Exists
    effect: NoSchedule

```


## Build and Deploy the Microservice

Create the ECR repository.
```bash
aws ecr create-repository \
    --repository-name ibmstocktrader/portfolioassistant \
    --region us-east-1 \
    --tags Key=owner,Value=ryan.claussen@kyndryl.com Key=purpose,Value="GH Actions Build" Key=solution,Value=stocktrader
```

```bash
aws ecr get-login-password --region us-east-1 | docker login --username AWS --password-stdin <Your Image Repo>.dkr.ecr.us-east-1.amazonaws.com
```


Build and push the image
```bash
mvn clean install -Dquarkus.container-image.build=true \
-Dquarkus.container-image.tag=latest \
-Dquarkus.container-image.group=ibmstocktrader \
-Dquarkus.container-image.registry=<Your Image Repo>.dkr.ecr.us-east-1.amazonaws.com && \
docker push <Your Image Repo>.dkr.ecr.us-east-1.amazonaws.com/ibmstocktrader/portfolioassistant:latest

```

Now deploy the pod via a deployment.yaml. Note we're deploying to the stocktrader namespace, so make sure you create that first if it doesn't exist or change to your namespace.

```bash
```yaml
#       Copyright 2025 Kyndryl, All Rights Reserved

#   Licensed under the Apache License, Version 2.0 (the "License");
#   you may not use this file except in compliance with the License.
#   You may obtain a copy of the License at

#       http://www.apache.org/licenses/LICENSE-2.0

#   Unless required by applicable law or agreed to in writing, software
#   distributed under the License is distributed on an "AS IS" BASIS,
#   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#   See the License for the specific language governing permissions and
#   limitations under the License.


#Deploy the pod
apiVersion: apps/v1
kind: Deployment
metadata:
  name: portfolioassistant
  labels:
    app: portfolioassistant-stock-trader
spec:
  replicas: 1
  selector:
    matchLabels:
      app: portfolioassistant
  template:
    metadata:
      labels:
        app: portfolioassistant
      annotations:
        git-repo: "https://github.com/IBMStockTrader/PortfolioAssistant"
    spec:
      containers:
      - name: portfolioassistant
        image: <Your Image Repo>.dkr.ecr.us-east-1.amazonaws.com/ibmstocktrader/portfolioassistant:latest
        ports:
          - containerPort: 9080
          - containerPort: 9443
        imagePullPolicy: Always
        resources:
          limits:
            cpu: 1000m
            memory: 2Gi
            ephemeral-storage: 256Mi
          requests:
            cpu: 500m
            memory: 1Gi
            ephemeral-storage: 32Mi
---
#Deploy the service
apiVersion: v1
kind: Service
metadata:
  name: portfolioassistant-service
  labels:
    app: portfolioassistant
spec:
  type: NodePort
  ports:
    - name: http
      protocol: TCP
      port: 9080
      targetPort: 9080
    - name: https
      protocol: TCP
      port: 9443
      targetPort: 9443
  selector:
    app: portfolioassistant

```


```bash
kubectl apply -f <name>.yml -n stocktrader

```

in another tab
```bash
kubectl get po -n stocktrader
kubectl --namespace stocktrader port-forward portfolioassistant-<Some pods> 8081:9080
```

In a third tab:
```bash
npm install -g wscat
wscat -c ws://localhost:8081/ws/stream 
```

If the websocket is secured by jwt (you get a 403 or 401 error above), find your jwt in the StockTrader UI/cookies and run like (change to `wss` once you have tls on there)
```bash
wscat -c ws://localhost:8081/ws/stream -H "Authorization: Bearer YOUR_JWT_TOKEN"
```