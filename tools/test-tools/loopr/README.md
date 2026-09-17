# loopr

## Introduction

This project is designed to simulate real-world end-users interacting with the StockTrader 
application through an external load balancer, such as an AWS Elastic LoadBalancer. The purpose is to test the application's performance, scalability, and reliability under various user scenarios.


## Key Features 

The load testing scenario involves the following key features:

1. **User Portfolio Creation**: The load test will initiate the creation of a new portfolio for a randomly generated user. This step represents the beginning of a user's interaction with the StockTrader application.

2. **Stock Purchase**: After portfolio creation, the load test will instruct users to buy a random number of shares (between 0 and 100) of a randomly selected stock. This action emulates the common behavior of users buying stock. 

3. **Stock Sale**: Subsequently, a random number of shares of the selected stock will be sold. This action mimics users' interactions with the application to sell their existing holdings.

4. **Portfolio Deletion**: The load test will conclude by instructing users to delete their portfolios, representing the end of a user's engagement with the application. 

5. **Think Time**: Between each of the above activities, there is a random "think time" period, simulating the intervals during which users ponder their next move or perform other actions on the platform.

6. **Page Navigation Steps**: The scenarios also includes page-navigation steps. These actions replicate the navigation users typically perform when interacting with the application. 

## Customization 

The load testing scenario is highly customizable, allowing you to tailor it to your specific testing requirements. You can get creative and make the following adjustments:

- **Additional Buy/Sell Actions**: You can add more buy and sell actions to simulate a more dynamic user behavior. For instance, you can introduce limit orders, market orders, or other trading strategies. 

- **Extended Think Time**: If you want to stress the application's ability to handle prolonged user interactions, you can increase the "think time" periods.

- **Additional Actions**: You can include other actions that users might perform on the StockTrader application, such as checking their portfolio balance, transferring funds, or setting up alerts.

- **Emulate Different User Types**: By modifying the scenario, you can emulate different types of user behavior. For example, you can simulate the behavior of novice investors, day traders, or long-term investors by changing the actions included in the scenario



## Prerequisites for Mac

Before running the tool, you need to ensure that the following prerequisites are met.

### 1. Install SDKMAN and SBT

First, you need to setup SDKMAN and SBT for managing Scala and SBT versions. Follow these steps:

#### a. Install SDKMAN

```shell
curl -s "https://get.sdkman.io" | bash 
```

#### b. Install Scala

```shell
sdk install scala
```

#### c. Install SBT

```shell
sdk install sbt
```

### 2. Clone the Repository 

Now, you need to clone the `test-tools` repository, which contains the StockTrader Load Testing Tool.

```shell
git clone https://github.com/IBMStockTrader/test-tools.git
```

### 3. Navigate to the Loopr Directory 

Change your working directory to the loopr repository

```shell
cd test-tools/loopr
```

### 4. Run the tool

You're now ready to run the StockTrader Load Testing Tool.

#### a. Clean and execute the test

Use SBT to clean and execute the test. Make sure to replace https://a872f86d8b0b74adc9a6fb3568be2118-cb3a347684a3ce53.elb.us-east-2.amazonaws.com:9443 with your specific test target:

```shell
sbt clean GatlingIt/test -DpublicBaseEndpoint=https://a872f86d8b0b74adc9a6fb3568be2118-cb3a347684a3ce53.elb.us-east-2.amazonaws.com:9443
```

