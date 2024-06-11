package io.github.lampajr;

import org.HdrHistogram.Histogram;

import java.io.FileNotFoundException;
import java.io.PrintStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;

public class Main {
    static long targetRPS = 10_000L;
    static long loadDurationInSeconds = 10;
    static long requestDurationInNanoseconds = 1_000L;
    static long serverDelayInNanoseconds = 10_000L;
    static double serverDelayFrequencyPercentage = 0.0;
    static Consumer<Long> waitTill = Main::spinWaitTill;

    /**
     * Simple args parser
     *
     * @param args cmd line arguments
     */
    private static void parseArgs(String[] args) {
        System.out.println("parsing " + Arrays.toString(args));

        var length = args.length;
        if (length > 0) {
            targetRPS = parseAsLong(args[0], targetRPS);
        }
        if (length > 1) {
            loadDurationInSeconds = parseAsLong(args[1], loadDurationInSeconds);
        }
        if (length > 2) {
            requestDurationInNanoseconds = parseAsLong(args[2], requestDurationInNanoseconds);
        }
        if (length > 3) {
            serverDelayInNanoseconds = parseAsLong(args[3], serverDelayInNanoseconds);
        }
        if (length > 4) {
            serverDelayFrequencyPercentage = parseAsDouble(args[4], serverDelayFrequencyPercentage);
        }
        if (length > 5) {
            switch (args[5].toLowerCase()) {
                case "classic":
                    System.out.println("using classic waitTill");
                    waitTill = Main::classicWaitTill;
                    break;
                case "vanilla":
                    System.out.println("using vanilla waitTill");
                    waitTill = Main::vanillaWaitTill;
                    break;
                default:
                    System.out.println("using default waitTill");
            }
        }
    }

    // For simplicity this is emulating an HTTP-like system with a closed model
    public static void main(String[] args) throws InterruptedException, FileNotFoundException {
        parseArgs(args);

        final Histogram serviceTimeHist = new Histogram(4);
        final Histogram responseTimeHist = new Histogram(4);

        // this is useful to schedule requests
        final long periodInNanoseconds = 1000_000_000L / targetRPS;
        System.out.println("computed period is " + periodInNanoseconds + " ns");

        // keep track of the current request
        final AtomicReference<Request> currentRequest = new AtomicReference<>();

        // a simple consumer that simulates a load generator
        Thread loadGenerator = new Thread(() -> {
            Thread me = Thread.currentThread();

            // for simplicity trigger the first request after periodInNanoseconds
            long nextFireTime = System.nanoTime() + periodInNanoseconds;
            int counter = 1;
            while (!me.isInterrupted()) {
                // send request, wait its completion and record times
                // if (System.nanoTime() > nextFireTime) {
                //    System.out.println("Sorry, I'm late handling request n. " + counter);
                // }
                // change this and see how results change
                waitTill.accept(nextFireTime);

                // send request and await response
                // nextFireTime represents the intended start time
                var req = new Request(System.nanoTime(), nextFireTime);
                currentRequest.set(req);
                if (!req.awaitCompletion()) {
                    // stop, server stopped
                    break;
                }

                // compute service/response time
                long completedTime = System.nanoTime();
                long serviceTime = completedTime - req.startTime;
                long responseTime = completedTime - req.intendedStartTime;

                // records values into histograms
                try {
                    serviceTimeHist.recordValue(serviceTime);
                    responseTimeHist.recordValue(responseTime);
                } catch (Exception e) {
                    System.out.println("error recording values for " + counter + ":" +
                          " completedTime=" + completedTime +
                          " serviceTime=" + serviceTime +
                          " responseTime=" + responseTime);
                }
                // compute next fire time based on previous fire time
                nextFireTime += periodInNanoseconds;
                counter++;
            }
        });

        // a simple producer that simulates a server
        Thread server = new Thread(() -> {
            // start the load generator at server startup
            loadGenerator.start();

            Thread me = Thread.currentThread();
            ThreadLocalRandom rnd = ThreadLocalRandom.current();

            while (!me.isInterrupted()) {
                // fetch request if not null, process it and then mark it as completed

                Request req;
                while ((req = currentRequest.getAndSet(null)) == null) {
                    // wait till there is a request in the currentRequest object
                    if (me.isInterrupted()) {
                        // check if the current thread has been interrupted
                        break;
                    }
                }

                if (req == null) {
                    // this can happen iff the thread has been interrupted while req is null
                    break;
                }

                // request received, simulate some work
                long delay = 0;
                if (serverDelayFrequencyPercentage > 0) {
                    if ((rnd.nextDouble() * 100) < serverDelayFrequencyPercentage) {
                        delay = serverDelayInNanoseconds;
//                        System.out.println("adding delay = " + delay);
                    }
                }

                // service work is the request duration + additional delay
                long realServiceWork = requestDurationInNanoseconds + delay;

                // emulate work
                spinWaitFor(realServiceWork);
                req.completed();
            }

            var last = currentRequest.getAndSet(null);
            if (last != null) {
                last.errored();
            }
        });


        String infos = "conducting test with:\n" +
                "   RPS               = " + targetRPS + " rps\n" +
                "   LOAD_DURATION     = " + loadDurationInSeconds + " s\n" +
                "   REQUEST_DURATION  = " + requestDurationInNanoseconds + " ns\n" +
                "   SERVER_DELAY      = " + serverDelayInNanoseconds + " ns\n" +
                "   SERVER_DELAY_FREQ = " + serverDelayFrequencyPercentage + " %\n";
        System.out.println(infos);

        // start the server together with the load generator
        server.start();

        // wait loadDurationInSeconds before stopping the load generator
        waitTill.accept(System.nanoTime() + (loadDurationInSeconds * 1_000_000_000L));
        // TimeUnit.SECONDS.sleep(loadDurationInSeconds);

        System.out.println("stopping simulation...");
        loadGenerator.interrupt();
        loadGenerator.join();
        System.out.println("simulation stopped");

        System.out.println("stopping server...");
        server.interrupt();
        server.join();
        System.out.println("server stopped");

        // save histograms
        String date = DateTimeFormatter.ofPattern("uuuuMMdd_HHmmss").format(LocalDateTime.now());
        try (PrintStream out = new PrintStream(String.format("hist/service-%s-%s.hgrm", String.join("-", args), date))) {
            serviceTimeHist.outputPercentileDistribution(out, 1.0);
            out.flush();
        }
        try (PrintStream out = new PrintStream(String.format("hist/response-%s-%s.hgrm", String.join("-", args), date))) {
            responseTimeHist.outputPercentileDistribution(out, 1.0);
            out.flush();
        }

    }

    /**
     * Wait for some provided time period
     *
     * @param period time to wait, expressed in nanoseconds
     */
    private static void spinWaitFor(long period) {
        var start = System.nanoTime();
        while ((System.nanoTime() - start) < period) {
            // wait till enough time is passed
        }
    }

    /**
     * Wait for some provided time period
     *
     * @param period time to wait, expressed in nanoseconds
     */
    private static void spinWaitForConfigurable(long period) {
        var endTime = System.nanoTime() + period;
        waitTill.accept(endTime);
    }

    /**
     * Wait till we reach a deadline
     *
     * @param end deadline expressed in nanoseconds
     */
    private static void spinWaitTill(long end) {
        while ((end - System.nanoTime()) > 0) {
            // wait till we reach the end time
        }
    }

    /**
     * Wait till we reach a deadline
     *
     * @param end deadline expressed in nanoseconds
     */
    private static void classicWaitTill(long end) {
        long toWait = end - System.nanoTime();
        if (toWait > 0) {
            try {
                TimeUnit.NANOSECONDS.sleep(toWait);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * Wait till we reach a deadline
     *
     * @param end deadline expressed in nanoseconds
     */
    private static void vanillaWaitTill(long end) {
        for (; ; ) {
            long toWait = end - System.nanoTime();
            if (toWait > 0) {
                LockSupport.parkNanos(toWait);
            }
            break;
        }
    }

    private static class Request {
        // actual start time
        private final long startTime;
        // scheduled start time
        private final long intendedStartTime;
        // request's result, true=success and false=error
        private volatile Boolean result;

        public Request(long startTime, long intendedStartTime) {
            this.startTime = startTime;
            this.intendedStartTime = intendedStartTime;
        }

        public void completed() {
            result = Boolean.TRUE;
        }

        public void errored() {
            result = Boolean.FALSE;
        }

        public Boolean awaitCompletion() {
            while (result == null) {
                // wait until the request completes/errors
            }
            return result;
        }
    }

    ///////////////////////
    ////// Utilities //////
    ///////////////////////

    private static long parseAsLong(String arg, long defaultValue) {
        try {
            return Long.parseLong(arg);
        } catch (NumberFormatException e) {
            System.out.println("unable to parse " + arg + ", using default value " + defaultValue);
            return defaultValue;
        }
    }

    private static double parseAsDouble(String arg, double defaultValue) {
        try {
            return Double.parseDouble(arg);
        } catch (NumberFormatException e) {
            System.out.println("unable to parse " + arg + ", using default value " + defaultValue);
            return defaultValue;
        }
    }

    private static int parseAsInt(String arg, int defaultValue) {
        try {
            return Integer.parseInt(arg);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
