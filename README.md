# Blitzy-StockTrader
Forked from IBMStockTrader's repos, this is a repo that is the parent repo of a number of repos that comprise the IBMStockTrader application

_From the original IBMStockTrader README_   


The IBMStockTrader application demonstrates how to build a cloud-native application out of a set of containerized microservices (each in their own repo under this org) that will run in Kubernetes.  It was initially created at IBM, and now is primarily maintained by Kyndryl (by the ex-IBMers that created it).  An intro article (that is a bit dated, but still useful) is at https://medium.com/hybrid-cloud-engineering/introducing-the-ibm-stock-trader-sample-b9b1ad6749e6 (and there are many other articles under https://medium.com/hybrid-cloud-engineering and https://medium.com/cloud-journey-optimization that use Stock Trader as their example).  There is also a recently published book (in paperback and Kindle) that uses Stock Trader as its example throughout, available at https://www.amazon.com/Practical-Cloud-Native-Java-Development-MicroProfile/dp/1801078807.

It originally ran in the (now-defunct) *IBM Cloud Private* product as its Kube implementation, but has since been successfully tested in the *IBM Kubernetes Service* (IKS), the *Elastic Kubernetes Service* (EKS) in AWS, the *Azure Kubernetes Service* (AKS), the *Google Kubernetes Engine* (GKE), the *Tanzu Kubernetes Grid* (TKG), and in the *OpenShift Container Platform* (in both IBM Cloud and in AWS).  Most of the microservices are *Java*-based, running in the *Open Liberty* application server.  Discussion occurs on Gitter, at https://gitter.im/IBMStockTrader/community - feel free to sign up and join in on the discussion!

This diagram shows how the microservices fit together, and what external services (databases, messaging products, API/function services, etc.) they utilize.  Note that only the ones with a solid border are mandatory - the rest are all optional, only installed when you want to enable additional bells and whistles.

![Architectural Diagram](https://raw.githubusercontent.com/IBMStockTrader/stocktrader-operator/master/images/stock-trader-aws.png)

A composite operator for the application as a whole exists, in the *stocktrader-operator* repo.  See its readme for information on how to install the operator into your cluster's OperatorHub, how to deploy the operator from there, and how to create an instance of the *StockTrader* CRD that it manages, and then how to run the application.  See each microservice's repository readme for details about what each does.

Note that there is an automated CI/CD pipeline that will automatically run a *GitHub Action* whenever there is a commit to one of the microservice repos, which will build the microservice and push the resulting image to the appropriate subdirectory of `quay.io/ibmstocktrader` (for example, if a change is made to the *portfolio* microservice, an image will get built and pushed to quay.io/ibmstocktrader/portfolio).  This *GitHub Action* will also update the image tag for that microservice in the CR yaml in the *stocktrader-gitops* repo to point to that newly pushed image.  Then a *GitHub Action* in that *stocktrader-gitops* repo will automatically do a `kubectl apply -f` of that yaml to the configured Kube cluster (such configuration is done via *GitHub secrets* at both the org-level and at the repo-level for the *stocktrader-gitops* repo).  The net is that, within a minute or so of committing a change, the updated code will be running in your cluster.

![CI/CD Pipeline](https://raw.githubusercontent.com/IBMStockTrader/stocktrader-operator/master/images/stock-trader-pipeline.png)

If any problems are encountered, open an issue against the appropriate microservice repo under this org.  For example, for an issue with the *stock-quote* microservice, go to https://github.com/IBMStockTrader/stock-quote/issues.  Pull requests are also very welcome (such as at https://github.com/IBMStockTrader/stock-quote/pulls).
