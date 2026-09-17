package com.ibm.hybrid.cloud.sample.portfolio.tasks;

import com.ibm.hybrid.cloud.sample.portfolio.pojo.LoopResult;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.Callable;

public class LegacyLoopTask implements Callable<List<LoopResult>> {
    private int threadIndexNumber = 0;
    private int numTimesToLoop = 0;
    private String auth = null;
    private String fullURL = null;

    public LegacyLoopTask(String url, int threadIndexNumber, int numTimesToLoop, String id, String pwd) {
        this.fullURL = url + "?id=Looper" + threadIndexNumber;
        this.threadIndexNumber = threadIndexNumber;
        this.numTimesToLoop = numTimesToLoop;
        String credentials = id + ":" + pwd;
        this.auth = "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes());
    }

    @Override
    public List<LoopResult> call() throws Exception {
        String result = null;
        LoopResult loopResult;
        List<LoopResult> loopResults = new ArrayList<>(numTimesToLoop);
        boolean success = false;
        try {
            for (int index = 1; index <= numTimesToLoop; index++) {
                System.out.println("Thread #" + threadIndexNumber + ", iteration #" + index);

                long start = System.currentTimeMillis();

                result = this.invokeREST("GET", fullURL + '_' + index);

//                System.out.println(result);

                long end = System.currentTimeMillis();
                System.out.println("Elapsed time for thread #" + threadIndexNumber + ", iteration #" + index + ": " + (end - start) + " ms");
                if (result.contains("Exception")) {
                    success = false;
                } else {
                    success = true;
                }
                loopResult = new LoopResult((end - start), success, result);
                loopResults.add(loopResult);
            }
        } catch (Throwable t) {
            loopResult = new LoopResult(-1, false, t.getMessage());
            t.printStackTrace();
            loopResults.add(loopResult);
        }
        return loopResults;
    }

    private String invokeREST(String verb, String uri) throws IOException {
        URL url = new URL(uri);

        HttpURLConnection conn = (HttpURLConnection) url.openConnection();

        conn.setRequestMethod(verb);
        conn.setRequestProperty("Content-Type", "text/plain");
        conn.setRequestProperty("Authorization", auth);
        conn.setDoOutput(true);
        InputStream stream = conn.getInputStream();

        String response = stringFromStream(stream);

        stream.close();

        return response; //I use JsonStructure here so I can return a JsonObject or a JsonArray
    }

    private String stringFromStream(InputStream in) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in));
        StringBuilder out = new StringBuilder();
        String line = null;
        while ((line = reader.readLine()) != null) {
            out.append(line + "\n");
        }
        return out.toString();
    }
}
