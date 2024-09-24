/**
 * Copyright 2014-2020 [fisco-dev]
 *
 * <p>Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 * <p>http://www.apache.org/licenses/LICENSE-2.0
 *
 * <p>Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.fisco.bcos.sdk.demo.perf;

import com.google.common.util.concurrent.RateLimiter;
import java.io.IOException;
import java.math.BigInteger;
import java.net.URL;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;
import me.tongfei.progressbar.ProgressBar;
import me.tongfei.progressbar.ProgressBarBuilder;
import me.tongfei.progressbar.ProgressBarStyle;
import org.fisco.bcos.sdk.demo.contract.Proxy;
import org.fisco.bcos.sdk.demo.contract.StableCoin;
import org.fisco.bcos.sdk.v3.BcosSDK;
import org.fisco.bcos.sdk.v3.client.Client;
import org.fisco.bcos.sdk.v3.crypto.keypair.CryptoKeyPair;
import org.fisco.bcos.sdk.v3.model.ConstantConfig;
import org.fisco.bcos.sdk.v3.model.TransactionReceipt;
import org.fisco.bcos.sdk.v3.model.callback.TransactionCallback;
import org.fisco.bcos.sdk.v3.transaction.model.exception.ContractException;
import org.fisco.bcos.sdk.v3.utils.ThreadPoolService;

/** @author monan */
public class PerformanceStableCoin {
    private static Client client;

    public static void usage() {
        System.out.println(" Usage:");
        System.out.println("===== PerformanceStableCoin test===========");
        System.out.println(
                " \t java -cp 'conf/:lib/*:apps/*' org.fisco.bcos.sdk.demo.perf.PerformanceStableCoin [groupId] [userCount] [count] [qps].");
    }

    public static void main(String[] args)
            throws ContractException, IOException, InterruptedException {
        try {
            String configFileName = ConstantConfig.CONFIG_FILE_NAME;
            URL configUrl = ParallelOkPerf.class.getClassLoader().getResource(configFileName);
            if (configUrl == null) {
                System.out.println("The configFile " + configFileName + " doesn't exist!");
                return;
            }

            if (args.length < 4) {
                usage();
                return;
            }
            String groupId = args[0];
            int userCount = Integer.valueOf(args[1]).intValue();
            Integer count = Integer.valueOf(args[2]).intValue();
            Integer qps = Integer.valueOf(args[3]).intValue();

            String configFile = configUrl.getPath();
            BcosSDK sdk = BcosSDK.build(configFile);
            client = sdk.getClient(groupId);
            ThreadPoolService threadPoolService =
                    new ThreadPoolService("DMCClient", Runtime.getRuntime().availableProcessors());

            start(groupId, userCount, count, qps, threadPoolService);

            threadPoolService.getThreadPool().awaitTermination(0, TimeUnit.SECONDS);
            System.exit(0);
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    public static void start(
            String groupId,
            int userCount,
            int count,
            Integer qps,
            ThreadPoolService threadPoolService)
            throws IOException, InterruptedException, ContractException {
        System.out.println(
                "====== Start test, user count: "
                        + userCount
                        + ", count: "
                        + count
                        + ", qps:"
                        + qps
                        + ", groupId: "
                        + groupId);

        RateLimiter limiter = RateLimiter.create(qps.intValue());

        CryptoKeyPair EOA = client.getCryptoSuite().getCryptoKeyPair().generateKeyPair();
        String eoaAddress = EOA.getAddress();
        StableCoin contract = StableCoin.deploy(client, client.getCryptoSuite().getCryptoKeyPair());
        String contractAddress = contract.getContractAddress();

        Proxy proxy = Proxy.deploy(client, EOA, contractAddress, eoaAddress, new byte[] {});

        StableCoin proxyStableCoin = StableCoin.load(proxy.getContractAddress(), client, EOA);
        proxyStableCoin.initialize(
                "FBCoin",
                "FBC",
                "CNY",
                BigInteger.valueOf(18),
                eoaAddress,
                eoaAddress,
                eoaAddress,
                false);

        proxyStableCoin.updateReserveBalance(BigInteger.valueOf(Long.MAX_VALUE));

        long averageBalance = Long.MAX_VALUE / (userCount * 2);

        CryptoKeyPair[] accounts = new CryptoKeyPair[userCount];
        AtomicLong[] summary = new AtomicLong[userCount];

        final Random random = new Random();
        random.setSeed(System.currentTimeMillis());

        System.out.println("Create account...");
        IntStream.range(0, userCount)
                .parallel()
                .forEach(
                        i -> {
                            CryptoKeyPair account;
                            limiter.acquire();
                            account = client.getCryptoSuite().getCryptoKeyPair();
                            accounts[i] = account;
                        });
        System.out.println("Create account finished!");

        System.out.println("Mint transactions...");
        {
            ProgressBar sendedBar =
                    new ProgressBarBuilder()
                            .setTaskName("Send   :")
                            .setInitialMax(userCount)
                            .setStyle(ProgressBarStyle.UNICODE_BLOCK)
                            .build();
            ProgressBar receivedBar =
                    new ProgressBarBuilder()
                            .setTaskName("Receive:")
                            .setInitialMax(userCount)
                            .setStyle(ProgressBarStyle.UNICODE_BLOCK)
                            .build();

            CountDownLatch transactionLatch = new CountDownLatch(userCount);
            AtomicLong totalCost = new AtomicLong(0);
            Collector collector = new Collector();
            collector.setTotal(userCount);

            IntStream.range(0, userCount)
                    .parallel()
                    .forEach(
                            i -> {
                                limiter.acquire();

                                CryptoKeyPair account = accounts[i];
                                long now = System.currentTimeMillis();
                                proxyStableCoin.mint(
                                        account.getAddress(),
                                        BigInteger.valueOf(averageBalance),
                                        new TransactionCallback() {
                                            @Override
                                            public void onResponse(TransactionReceipt receipt) {

                                                long cost = System.currentTimeMillis() - now;
                                                collector.onMessage(receipt, cost);

                                                receivedBar.step();
                                                transactionLatch.countDown();
                                                totalCost.addAndGet(
                                                        System.currentTimeMillis() - now);
                                            }
                                        });

                                sendedBar.step();
                            });
            transactionLatch.await();

            sendedBar.close();
            receivedBar.close();
            collector.report();
            System.out.println("Mint transactions finished!");
        }
        System.out.println("===============================================");

        System.out.println("Approve transactions...");
        {
            ProgressBar sendedBar =
                    new ProgressBarBuilder()
                            .setTaskName("Send   :")
                            .setInitialMax(userCount)
                            .setStyle(ProgressBarStyle.UNICODE_BLOCK)
                            .build();
            ProgressBar receivedBar =
                    new ProgressBarBuilder()
                            .setTaskName("Receive:")
                            .setInitialMax(userCount)
                            .setStyle(ProgressBarStyle.UNICODE_BLOCK)
                            .build();

            CountDownLatch transactionLatch = new CountDownLatch(userCount);
            AtomicLong totalCost = new AtomicLong(0);
            Collector collector = new Collector();
            collector.setTotal(userCount);

            IntStream.range(0, userCount)
                    .parallel()
                    .forEach(
                            i -> {
                                limiter.acquire();

                                CryptoKeyPair account = accounts[i];
                                long now = System.currentTimeMillis();
                                StableCoin stableCoin =
                                        StableCoin.load(
                                                proxyStableCoin.getContractAddress(),
                                                client,
                                                account);
                                stableCoin.approve(
                                        eoaAddress,
                                        BigInteger.valueOf(averageBalance),
                                        new TransactionCallback() {
                                            @Override
                                            public void onResponse(TransactionReceipt receipt) {

                                                long cost = System.currentTimeMillis() - now;
                                                collector.onMessage(receipt, cost);

                                                receivedBar.step();
                                                transactionLatch.countDown();
                                                totalCost.addAndGet(
                                                        System.currentTimeMillis() - now);
                                            }
                                        });
                                sendedBar.step();
                            });
            transactionLatch.await();

            sendedBar.close();
            receivedBar.close();
            collector.report();
            System.out.println("Approve transactions finished!");
        }
        System.out.println("===============================================");

        System.out.println("transfer transactions...");
        {
            ProgressBar sendedBar =
                    new ProgressBarBuilder()
                            .setTaskName("Send   :")
                            .setInitialMax(count)
                            .setStyle(ProgressBarStyle.UNICODE_BLOCK)
                            .build();
            ProgressBar receivedBar =
                    new ProgressBarBuilder()
                            .setTaskName("Receive:")
                            .setInitialMax(count)
                            .setStyle(ProgressBarStyle.UNICODE_BLOCK)
                            .build();

            CountDownLatch transactionLatch = new CountDownLatch(count);
            AtomicLong totalCost = new AtomicLong(0);
            Collector collector = new Collector();
            collector.setTotal(count);

            IntStream.range(0, count)
                    .parallel()
                    .forEach(
                            i -> {
                                limiter.acquire();
                                final int index = i % accounts.length;

                                CryptoKeyPair account = accounts[index];
                                long now = System.currentTimeMillis();
                                proxyStableCoin.transferFrom(
                                        account.getAddress(),
                                        eoaAddress,
                                        BigInteger.valueOf(1),
                                        new TransactionCallback() {
                                            @Override
                                            public void onResponse(TransactionReceipt receipt) {
                                                long cost = System.currentTimeMillis() - now;
                                                collector.onMessage(receipt, cost);

                                                receivedBar.step();
                                                transactionLatch.countDown();
                                                totalCost.addAndGet(
                                                        System.currentTimeMillis() - now);
                                            }
                                        });

                                sendedBar.step();
                            });
            transactionLatch.await();

            sendedBar.close();
            receivedBar.close();
            collector.report();
        }
        System.out.println("Transfer transactions finished!");
    }
}
