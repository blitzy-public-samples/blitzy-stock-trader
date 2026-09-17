package com.ibm.hybrid.cloud.sample.portfolio.tasks;

import com.ibm.hybrid.cloud.sample.portfolio.pojo.LoopResult;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Base64;
import java.util.concurrent.Callable;

public class FlatLoopTask implements Callable<LoopResult> {
    private int itemId = 0;
    private String auth = null;
    private String fullURL = null;

    public FlatLoopTask(String url, int itemId, String id, String pwd) {
        fullURL = url + "?id=Looper" + itemId;
        this.itemId = itemId;
        String credentials = id + ":" + pwd;
        auth = "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes());
    }

    @Override
    public LoopResult call() throws Exception {
        String result = null;
        LoopResult loopResult;
        boolean successful = false;
        try {
                System.out.println("Item #: "+itemId);

                long start = System.currentTimeMillis();

                result = invokeREST("GET", fullURL);

                //System.out.println(result);

                long end = System.currentTimeMillis();

                System.out.println("Elapsed time for item # "+itemId+": "+(end-start)+" ms");
                if(result.contains("Exception")){
                    successful = false;
                }else{
                    successful = true;
                }
            loopResult = new LoopResult((end-start), successful,result);
            return loopResult;
        } catch (Throwable t) {
            loopResult = new LoopResult(-1, false, t.getMessage());
            t.printStackTrace();
            return loopResult;
        }
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
            out.append(line+"\n");
        }
        return out.toString();
    }
}
