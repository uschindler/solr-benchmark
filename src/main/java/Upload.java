/*
 * Copyright (c) 2021, Azul Systems
 *
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * * Redistributions of source code must retain the above copyright notice, this
 *   list of conditions and the following disclaimer.
 *
 * * Redistributions in binary form must reproduce the above copyright notice,
 *   this list of conditions and the following disclaimer in the documentation
 *   and/or other materials provided with the distribution.
 *
 * * Neither the name of [project] nor the names of its
 *   contributors may be used to endorse or promote products derived from
 *   this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *
 */

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.invoke.MethodHandles;
import java.util.Map;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrRequest.METHOD;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.impl.ConcurrentUpdateHttp2SolrClient;
import org.apache.solr.client.solrj.impl.Http2SolrClient;
import org.apache.solr.client.solrj.request.GenericSolrRequest;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.util.NamedList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonStreamParser;

/**
 * @author deepakr
 */
public class Upload {
    private static Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
    
    private static final Gson gson = new GsonBuilder().disableJdkUnsafe().create();
    private static InputStream inputStream;
    private static JsonStreamParser jsonStreamParser;

    private static final String solrCollection = System.getProperty("c", "test");
    private static final String hostnamePortList = System.getProperty("hp", "localhost:8983");
    private static final long maxSize = Long.getLong("sz", 5L * 1024L * 1024L * 1024L);
    private static final int NUM_OF_THREADS = Integer.getInteger("t", 5);

    public static void main(String[] args) throws IOException, SolrServerException, InterruptedException {
        if (args.length != 1) {
            log.error("First argument must be json file to be indexed in Solr");
            System.exit(3);
        }
        final File inputFile = new File(args[0]);
        inputStream = new FileInputStream(inputFile);
        jsonStreamParser = new JsonStreamParser(new InputStreamReader(inputStream));

        try (final var solrClient = new Http2SolrClient
            .Builder("http://" + hostnamePortList + "/solr/" + solrCollection)
            .build();
        final var bulkClient = new ConcurrentUpdateHttp2SolrClient
            .Builder("http://" + hostnamePortList + "/solr/" + solrCollection, solrClient)
            .withThreadCount(NUM_OF_THREADS)
            .build()) {
          log.info("Delete all documents and committing empty index...");
          bulkClient.deleteByQuery("*:*");
          bulkClient.optimize();
          bulkClient.commit();
          log.info("Start indexing...");
          SolrInputDocument solrInputDocument;
          int count = 0;
          while ((solrInputDocument = getMeTheNextSolrInputDoc()) != null) {
              try {
                  bulkClient.add(solrInputDocument);
                  count++;
                  if (count % 10_000 == 0) {
                    bulkClient.blockUntilFinished();
                    bulkClient.commit();
                    log.info("Number of docs indexed so far : " + count + " (out of ~12.8M docs)");
                    long size = getIndexSize(solrClient);
                    log.info("Size of index in bytes so far : " + size);
                    if (size > maxSize) {
                      log.info("Size exceeded 5 GiB, stopping indexing after " + count + " documents.");
                      break;
                    }
                  }
              } catch (SolrServerException | IOException e) {
                  log.info("Error while indexin doc: " + e);
              }
          }
          log.info("Indexing done. Optimizing to one segment and committing...");
          bulkClient.optimize();
          bulkClient.commit();
          long finalSize = getIndexSize(solrClient);
          log.info("Index created. Final index size in bytes: " + finalSize);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> getMeTheNextJsonDoc() {
        while (jsonStreamParser.hasNext()) {
            JsonElement e = jsonStreamParser.next();
            if (e.isJsonObject()) {
                return gson.fromJson(e, Map.class);
            }
        }
        return null;
    }

    private static SolrInputDocument getMeTheNextSolrInputDoc() {
        Map<String, String> jsonDoc = getMeTheNextJsonDoc();
        if (jsonDoc == null) return null;

        SolrInputDocument solrInputDocument = new SolrInputDocument();
        for (String key : jsonDoc.keySet()) {
            solrInputDocument.addField(key, jsonDoc.get(key));
        }
        return solrInputDocument;
    }
    
    @SuppressWarnings("unchecked")
    private static long getIndexSize(SolrClient solrClient) throws IOException, SolrServerException {
      GenericSolrRequest admin = new GenericSolrRequest(METHOD.GET, "/admin/segments", null);
      var result = solrClient.request(admin);
      var segments = (NamedList<Object>) result.get("segments");
      return segments.asMap().values().stream().mapToLong(v -> (((NamedList<Number>) v).get("sizeInBytes")).longValue()).sum();
    }
}
