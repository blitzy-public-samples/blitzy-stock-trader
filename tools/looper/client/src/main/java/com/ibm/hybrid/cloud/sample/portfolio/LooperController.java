package com.ibm.hybrid.cloud.sample.portfolio;

import com.ibm.hybrid.cloud.sample.portfolio.pojo.LoopResult;
import com.ibm.hybrid.cloud.sample.portfolio.tasks.FlatLoopTask;
import com.ibm.hybrid.cloud.sample.portfolio.tasks.LegacyLoopTask;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.LongSummaryStatistics;
import java.util.concurrent.*;
import java.util.stream.Collectors;

public class LooperController {

	private static long totalNumberToRun;

	public static void main(String[] args) {
		if (args.length == 3) try {
			System.out.print("User ID: ");
			String id = System.console().readLine();

			System.out.print("Password: ");
			String pwd = new String(System.console().readPassword());
			System.out.println();

			loop(args[0], Integer.parseInt(args[1]), Integer.parseInt(args[2]), id, pwd);
		} catch (Throwable t) {
			t.printStackTrace();
		} else if (args.length == 4) try {
			System.out.print("User ID: ");
			String id = System.console().readLine();

			System.out.print("Password: ");
			String pwd = new String(System.console().readPassword());
			System.out.println();

			loopFaster(args[0], Integer.parseInt(args[1]), Integer.parseInt(args[2]), id, pwd);
		} catch (Throwable t) {
			t.printStackTrace();
		} else {
			System.out.println("Usage:   loopctl <optionalUrl> <count> <threads> <blank|flatLooper>");
			System.out.println("Looper will run <count>*<threads> runs of StockTrader. ");
			System.out.println("Example: loopctl http://looper-service:9080/looper 5000 20");
			System.out.println("Result:  Looper runs 100,000 times total (5000 iterations run across each one of 20 parallel threads)");
			System.out.println("Example: loopctl http://looper-service:9080/looper 5000 20 flatLooper");
			System.out.println("Result:  Looper runs 100,000 times total. " +
					"(100,000 tasks spread across 20 parallel threads/executors, each task pulled from a queue as an executor finishes a task).");
		}
	}

	public static void loop(String url, int times, int threads, String id, String pwd) throws InterruptedException {
		// Create a fixed size thread pool based on how many threads we want
		ExecutorService fixedThreadPoolExecutor = Executors.newFixedThreadPool(threads);

		// Create a queue/list to hold all the tasks we want to run.
		List<LegacyLoopTask> tasksToExecute = new ArrayList<>(threads);

		LooperController.totalNumberToRun = threads*times;

		// Create a single task for each of the threads. Each task will then loop a set number of times.
		for (int index=1; index<=threads; index++) {
			LegacyLoopTask task = new LegacyLoopTask(url, index, times, id, pwd);
			tasksToExecute.add(task);
		}
		// Invoke all the threads at the same time and collect the results.
		List<LoopResult> results = fixedThreadPoolExecutor.invokeAll(tasksToExecute) // Invoke all the tasks at the same time
				.parallelStream() // Process the results in parallel.
				.map(loopResultFuture -> {
					try {
						// This retrieves the result of the call.
						// It is a blocking call until the task completes or fails.
						return loopResultFuture.get();
					} catch (Exception e) {
						// Failure in task.
						throw new IllegalStateException(e);
					}
				}).flatMap(Collection::stream).collect(Collectors.toList());

		calculateStatistics(results);

		// All done. Let's shut things down.
		try {
			System.out.println("Attempting to shutdown.");
			fixedThreadPoolExecutor.shutdown();
			fixedThreadPoolExecutor.awaitTermination(20, TimeUnit.MINUTES);
		}
		catch (InterruptedException e) {
			System.err.println("Tasks interrupted, forcing shutdown.");
		}
		finally {
			if (!fixedThreadPoolExecutor.isTerminated()) {
				System.err.println("Cancel non-finished tasks and terminate.");
			}
			fixedThreadPoolExecutor.shutdownNow();
			System.out.println("Shutdown finished.");
		}
	}

	public static void loopFaster(String url, int times, int threads, String id, String pwd) throws InterruptedException {
		// Create a fixed size thread pool based on how many threads we want
		ExecutorService fixedThreadPoolExecutor = Executors.newFixedThreadPool(threads);

		// Create a queue/list to hold all the tasks we want to run. Here we create a big queue for every task.
		List<Callable<LoopResult>> tasksToExecute = new ArrayList<Callable<LoopResult>>(times*threads);

		LooperController.totalNumberToRun = threads*times;

		// Create a task for every single item we want to run. There are no inner loops so if we want 10 threads of 15 loops
		// we create 150 tasks (10*15) and add them to our queue.
		for (int index=1; index<=(LooperController.totalNumberToRun); index++) {
			FlatLoopTask task = new FlatLoopTask(url, index, id, pwd);
			tasksToExecute.add(task);
		}
		// Execute all the tasks and collect the results.
		List<LoopResult> allResults = fixedThreadPoolExecutor.invokeAll(tasksToExecute)
				.parallelStream()
				.map(loopResultFuture -> {
					try {
						// It is a blocking call until the task completes or fails.
						return loopResultFuture.get();
					} catch (Exception e) {
						// Failure in task.
						throw new IllegalStateException(e);
					}
		}).collect(Collectors.toList());

		calculateStatistics(allResults);

		// All done. Let's shut things down.
		try {
			System.out.println("Attempting to shutdown.");
			fixedThreadPoolExecutor.shutdown();
			fixedThreadPoolExecutor.awaitTermination(20, TimeUnit.MINUTES);
		}
		catch (InterruptedException e) {
			System.err.println("Tasks interrupted, forcing shutdown.");
		}
		finally {
			if (!fixedThreadPoolExecutor.isTerminated()) {
				System.err.println("Cancel non-finished tasks and terminate.");
			}
			fixedThreadPoolExecutor.shutdownNow();
			System.out.println("Shutdown finished.");
		}
	}

	public static void calculateStatistics(List<LoopResult> results) {
		// Count the number of successful runs.
		long successfulRuns = results.parallelStream().filter(LoopResult::isSuccessfulRun).count();

		// Then get all the items that failed in the run.
		List<LoopResult> failedItems = results.parallelStream()
				.filter(loopResult -> !loopResult.isSuccessfulRun())
				.collect(Collectors.toList());

		// Count the failed runs.
		long failedRuns = failedItems.parallelStream().count();

		if(failedRuns > 0) {
			System.out.println("There were " + failedRuns + " failed runs out of " + LooperController.totalNumberToRun);
			System.out.println("Here are their failure messages: ");
			failedItems.forEach(System.out::println);
		}

		System.out.println("There were " + successfulRuns + " successful runs out of " + LooperController.totalNumberToRun);

		// Calculate basic statistics from the run
		LongSummaryStatistics successfulStats = results.parallelStream()
				.filter(LoopResult::isSuccessfulRun)
				.mapToLong(LoopResult::getLoopDuration)
				.summaryStatistics();

		System.out.println("Here are some stats about the successful runs:");
		System.out.println("Shortest run " + successfulStats.getMin() + " ms");
		System.out.println("Longest run " + successfulStats.getMax() + " ms");
		System.out.println("Average run " + successfulStats.getAverage()+ " ms");
	}
}
