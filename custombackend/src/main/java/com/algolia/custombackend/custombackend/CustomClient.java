
package com.algolia.custombackend.custombackend;

import android.os.AsyncTask;
import android.os.Handler;
import android.os.Looper;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Log;

import com.algolia.custombackend.helpers.Helpers;
import com.algolia.instantsearch.core.searchclient.SearchClient;
import com.algolia.instantsearch.core.searchclient.SearchResultsHandler;
import com.algolia.search.saas.AlgoliaException;
import com.algolia.search.saas.Query;
import com.algolia.search.saas.Request;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.zip.GZIPInputStream;

public class CustomClient extends SearchClient<JSONObject, JSONObject> {

    public static class AsyncRequest extends AsyncTask<JSONObject, Boolean, JSONObject> implements Request {

        SearchResultsHandler<JSONObject> completionHandler;

        AsyncRequest(@NonNull SearchResultsHandler<JSONObject> completionHandler) {
            this.completionHandler = completionHandler;
        }

        @Override
        public void cancel() {

        }

        @Override
        public boolean isFinished() {
            return false;
        }

        @Override
        protected JSONObject doInBackground(JSONObject... customSearchParameters) {
            InputStream stream = null;
            HttpURLConnection hostConnection;
            JSONObject searchParameters = customSearchParameters[0];
            try {
                // Build URL. TODO: bonsai keeps disabling, find a long-term solution
                String urlString = "https://guys-first-sandbox-8228827598.eu-central-1.bonsaisearch.net/concerts/_search?";
                URL hostURL = new URL(urlString);

                String basicAuth = null;
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    basicAuth = "Basic " + new String(android.util.Base64.encode("dvilzsbbyw:7le70vhxx9".getBytes(), android.util.Base64.NO_WRAP));
                }

                // Open connection.
                hostConnection = (HttpURLConnection) hostURL.openConnection();
                hostConnection.setRequestProperty("Authorization", basicAuth);

                // Headers
                hostConnection.setRequestProperty("Accept-Encoding", "gzip");
                hostConnection.setRequestProperty("Content-Type", "application/json");
                hostConnection.setRequestMethod("POST");

                DataOutputStream printout = new DataOutputStream(hostConnection.getOutputStream());
                printout.writeBytes(searchParameters.toString());
                printout.flush();
                printout.close();

                // read response
                int code = hostConnection.getResponseCode();
                final boolean codeIsError = code / 100 != 2;
                stream = codeIsError ? hostConnection.getErrorStream() : hostConnection.getInputStream();
                if (stream == null) {
                    throw new IOException(String.format("Null stream when reading connection (status %d)", code));
                }

                final byte[] rawResponse;
                String encoding = hostConnection.getContentEncoding();
                if (encoding != null && encoding.equals("gzip")) {
                    rawResponse = Helpers._toByteArray(new GZIPInputStream(stream));
                } else {
                    rawResponse = Helpers._toByteArray(stream);
                }

                // handle http errors
                if (codeIsError) {
                    if (code / 100 == 4) {
                        throw new RuntimeException(Helpers._getJSONObject(rawResponse).getString("message"));
                    }
                }

                JSONObject results = Helpers._getJSONObject(rawResponse);
                this.completionHandler.requestCompleted(results, null);
                return results;

            } catch (Exception e) { // fatal
                Log.e("AsyncTask error", "doInBackground: " + e.getMessage());
                this.completionHandler.requestCompleted(null, e);
                return null;
            } finally {
                if (stream != null) {
                    try {
                        stream.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        }
    }

    @Override
    public Request search(@Nullable JSONObject query, @Nullable final SearchResultsHandler<JSONObject> completionHandler) {
        AsyncRequest request = new AsyncRequest(new SearchResultsHandler<JSONObject>() {
            @Override
            public void requestCompleted(final JSONObject content, final Exception error) {

                Handler h = new Handler(Looper.getMainLooper());
                Runnable r = new Runnable() {
                    @Override
                    public void run() {
                        if (error != null) {
                            completionHandler.requestCompleted(null, new AlgoliaException((error.getMessage())));
                        } else {
                            completionHandler.requestCompleted(content, null);
                        }
                    }
                };
                h.post(r);

            }
        });
        request.execute(query);
        return request;
    }

    @Override
    public JSONObject map(@NonNull Query query) {
        JSONObject rootObj = new JSONObject();
        try {

            JSONObject queryObj = new JSONObject();
            JSONArray fieldsArr = new JSONArray();
            fieldsArr.put("location");
            fieldsArr.put("name");

            queryObj.putOpt("fields", fieldsArr);
            queryObj.putOpt("type", "phrase_prefix");
            queryObj.putOpt("query", query.getQuery() != null ? query.getQuery() : "");

            JSONObject multiMatchObj = new JSONObject();
            multiMatchObj.putOpt("multi_match", queryObj);

            JSONArray mustArrObj = new JSONArray();
            mustArrObj.put(multiMatchObj);

            JSONObject mustObj = new JSONObject();
            mustObj.putOpt("must", mustArrObj);

            JSONObject boolObj = new JSONObject();
            boolObj.putOpt("bool", mustObj);

            rootObj.putOpt("query", multiMatchObj);
            rootObj.putOpt("size", query.getHitsPerPage());
            rootObj.putOpt("from", query.getPage() * query.getHitsPerPage());

            if (query.getAttributesToHighlight().length != 0) {
                JSONObject highlightFields = new JSONObject();
                for (String field : query.getAttributesToHighlight()) {
                    highlightFields.putOpt(field, new JSONObject());
                }
                JSONObject highlightObject = new JSONObject();
                highlightObject.putOpt("fields", highlightFields);

                if (query.getHighlightPreTag() != null && query.getHighlightPostTag() != null) {
                    highlightObject.putOpt("pre_tags", query.getHighlightPreTag());
                    highlightObject.putOpt("post_tags", query.getHighlightPostTag());
                }
                rootObj.putOpt("highlight", highlightObject);
            }
        } catch (Exception e) {
        }
        return rootObj;
    }

    @Override
    public JSONObject map(@NonNull JSONObject customSearchResults) {
        if (customSearchResults == null) {
            return null;
        }
        JSONObject hitsContent = customSearchResults.optJSONObject("hits");
        if (hitsContent == null) {
            return null;
        }
//        Map<String, Object> map = new HashMap<>();
        JSONObject obj = new JSONObject();
        try {
            obj.putOpt("hits", hitsContent.optJSONArray("hits"));
            obj.putOpt("nbHits", hitsContent.optInt("total"));
            obj.putOpt("query", "test");
            obj.putOpt("params", "testparams");
            obj.putOpt("processingTimeMS", customSearchResults.optInt("took"));
        } catch (Exception e) {
            return null;
        }
        return obj;
    }
}
