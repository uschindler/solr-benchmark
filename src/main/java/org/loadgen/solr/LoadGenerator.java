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

package org.loadgen.solr;

import java.lang.invoke.MethodHandles;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.apache.solr.client.solrj.impl.Http2SolrClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class LoadGenerator {
    private static Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    protected String hostnamePortList;
    protected String solrCollection;
    protected int numberOfThreads;
    protected int numberOfClients;

    protected long skipDurationInSec;
    protected long runDurationInSec;
    protected int targetThroughput;
    protected double updatePercentage;

    protected double operationStartTimeInSec;
    protected double operationEndTimeInSec;

    protected boolean collectLatencyMetrics;

    private List<Future<Long>> listOfFutures;
    protected QueryWorker[] arrayOfQueryWorkers;
    protected Consumer<Long> externalMetricsConsumer;

    private MetricsCollector metricsCollector;
    private Timer progressTrackingTimer;

    public void applyConfig(BenchConfig solrConfig) {
        this.setHostnamePortList(solrConfig.hostnamePortList)
            .setSolrCollection(solrConfig.solrCollection)
            .setNumberOfThreads(solrConfig.maxNumberOfThreads)
            .setNumberOfClients(solrConfig.maxNumberOfClients)
            .setSkipDurationInSec(solrConfig.benchmarkMeasurementSkipDuration)
            .setCollectLatencyMetrics(solrConfig.recordingLatency)
            .setUpdatePercentage(solrConfig.writePercent);
    }

    public LoadGenerator setHostnamePortList(String hostnamePortList) {
        this.hostnamePortList = hostnamePortList;
        return this;
    }

    public LoadGenerator setSolrCollection(String solrCollection) {
        this.solrCollection = solrCollection;
        return this;
    }

    public LoadGenerator setNumberOfThreads(int numberOfThreads) {
        this.numberOfThreads = numberOfThreads;
        return this;
    }

    public LoadGenerator setNumberOfClients(int numberOfClients) {
        this.numberOfClients = numberOfClients;
        return this;
    }

    public LoadGenerator setSkipDurationInSec(long skipDurationInSec) {
        this.skipDurationInSec = skipDurationInSec;
        return this;
    }

    public LoadGenerator setRunDurationInSec(long runDurationInSec) {
        this.runDurationInSec = runDurationInSec;
        return this;
    }

    public LoadGenerator setTargetThroughput(int targetThroughput) {
        this.targetThroughput = targetThroughput;
        return this;
    }

    public LoadGenerator setCollectLatencyMetrics(boolean collectLatencyMetrics) {
        this.collectLatencyMetrics = collectLatencyMetrics;
        return this;
    }

    public LoadGenerator setUpdatePercentage(double updatePercentage) {
        this.updatePercentage = updatePercentage;
        // Having known the % of work intended to be updates, split the resources/work proportionately
        this.numberOfThreads  = (int)(numberOfThreads  * getScaleFactor());
        this.numberOfClients  = (int)(numberOfClients  * getScaleFactor());
        this.targetThroughput = (int)(targetThroughput * getScaleFactor());

        return this;
    }

    public double getOperationStartTimeInSec() {
        return operationStartTimeInSec;
    }

    public void setOperationStartTimeInSec() {
        this.operationStartTimeInSec = System.currentTimeMillis() / 1000.0;
    }

    public double getOperationEndTimeInSec() {
        return operationEndTimeInSec;
    }

    public void setOperationEndTimeInSec() {
        this.operationEndTimeInSec = System.currentTimeMillis() / 1000.0;
    }

    public long getTotalRunDuration() {
        return this.skipDurationInSec + this.runDurationInSec;
    }

    abstract protected String getOperationName();
    abstract protected double getScaleFactor();
    abstract protected QueryWorker getQueryWorkerInstance();

    private void createAndConfigureWorkers(long durationToRunInSec) {
        if (numberOfThreads == 0) {
            return;
        }

        listOfFutures       = new ArrayList<>(numberOfThreads);
        arrayOfQueryWorkers = new QueryWorker[numberOfThreads];

        for (int i = 0; i < numberOfThreads; i++) {
            arrayOfQueryWorkers[i] = this.getQueryWorkerInstance();
            arrayOfQueryWorkers[i].setRunDurationInSec(durationToRunInSec);
            arrayOfQueryWorkers[i].setQueryWorkerStats(new QueryWorkerStats());

            // If throughputExpectedToBeAchievedByCurrentWorker somehow becomes 0, RateLimiter.create will throw exception
            // Simply set a ttpt to min value 1 in case it drops below that value
            final int throughputExpectedToBeAchievedByCurrentWorker = (int)(Math.ceil((targetThroughput * 1.0) / numberOfThreads));
            arrayOfQueryWorkers[i].setRateLimiter(ThroughputController.getInstance(Math.max(throughputExpectedToBeAchievedByCurrentWorker, 1)));
        }

        if (numberOfClients >= numberOfThreads) {
            for (int i = 0; i < numberOfClients; i++) {
                arrayOfQueryWorkers[i % numberOfThreads].addSolrClient(
                        new Http2SolrClient
                                .Builder("http://" + hostnamePortList + "/solr/" + solrCollection)
                                .build()
                );
            }
        } else {
            for (int i = 0; i < numberOfThreads; i++) {
                arrayOfQueryWorkers[i % numberOfThreads].addSolrClient(
                        new Http2SolrClient
                                .Builder("http://" + hostnamePortList + "/solr/" + solrCollection)
                                .build()
                );
            }
        }
    }

    private void startWorkers() {
        ExecutorService executorService = Executors.newFixedThreadPool(numberOfThreads);
        for (int i = 0; i < numberOfThreads; i++) {
            listOfFutures.add(executorService.submit(arrayOfQueryWorkers[i]));
        }
        executorService.shutdown();
    }

    public void startBenchmark() {
        final long totalRunDurationInSec = getTotalRunDuration();
        if (totalRunDurationInSec == 0 || numberOfThreads == 0 || numberOfClients == 0) {
            log.warn(String.format("Total run duration (skipDuration + measurement) : %d sec | " +
                            "thread count : %d | " +
                            "numberOfClients = %d\n" +
                            "\tAll the above must be non zero\n" +
                            "\tSkipping this operation (%s)\n",
                    totalRunDurationInSec, numberOfThreads, numberOfClients, this.getOperationName()));
            return;
        }

        printConfig();
        createAndConfigureWorkers(totalRunDurationInSec);

        if (collectLatencyMetrics) {
            metricsCollector = new MetricsCollector(this);
            metricsCollector.start();
        }

        setOperationStartTimeInSec();

        progressTrackingTimer = new Timer();
        progressTrackingTimer.schedule(new TimerTask() {
            int counter = 5;
            @Override
            public void run() {
                System.out.print("\rApprox. ETA for operation " + getOperationName() + " to complete : " +
                        (getTotalRunDuration() - (counter ++)) + " sec");
            }// run method
        }, TimeUnit.SECONDS.toMillis(5), TimeUnit.SECONDS.toMillis(1));

        startWorkers();
    }

    public void waitForBenchmarkRunToFinish() throws ExecutionException, InterruptedException {
        if (listOfFutures == null) return;

        final int numberOfWorkers = listOfFutures.size();
        for (int i = 0; i < numberOfWorkers; i++) {
            listOfFutures.get(i).get();
        }

        if (collectLatencyMetrics) {
            metricsCollector.stop();
        }

        progressTrackingTimer.cancel();
        System.out.println(); // start new line to allow logging to proceed
        setOperationEndTimeInSec();
        closeAllClientConnections();
    }

    private void closeAllClientConnections() {
        for (int i = 0; i < arrayOfQueryWorkers.length; i++) {
            arrayOfQueryWorkers[i].closeClientConnections();
        }
    }

    public void printConfig() {
        log.info(" ======================== " + this.getOperationName() + " ======================== ");
        try {
            log.info(String.format("%-30s %s %s", "hostname", ":",
                    InetAddress.getLocalHost().getHostName()));
        } catch (UnknownHostException e) {
            e.printStackTrace();
        }
        log.info(String.format("%-30s %s %s", "hostnamePortList", ":", hostnamePortList));
        log.info(String.format("%-30s %s %s", "solrCollection", ":", solrCollection));
        log.info(String.format("%-30s %s %s", "numberOfThreads", ":", numberOfThreads));
        log.info(String.format("%-30s %s %s", "numberOfClients", ":", numberOfClients));
        log.info(String.format("%-30s %s %s", "skipDurationInSec", ":", skipDurationInSec));
        log.info(String.format("%-30s %s %s", "runDurationInSec", ":", runDurationInSec));
        log.info(String.format("%-30s %s %s", "targetThroughput", ":", targetThroughput));
        //log.info(String.format("%-30s %s %s", "updatePercentage", ":", updatePercentage));
    }

    public long getTotalRequestsSentFromAllWorker() {
        if (listOfFutures == null) return 0;

        long totalRequests = 0;
        final int numberOfWorkers = listOfFutures.size();
        for (int i = 0; i < numberOfWorkers; i++) {

            if (!listOfFutures.get(i).isDone()) {
                log.warn("WARNING : One or more worker is still running ...");
                return 0;
            }

            try {
                totalRequests +=  listOfFutures.get(i).get();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return totalRequests;
    }

    public void setupExternalMetricsConsumer(Consumer<Long> externalLatencyRecorder) {
        this.externalMetricsConsumer = externalLatencyRecorder;
    }
}
