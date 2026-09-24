/* SPDX-License-Identifier: MIT */
package dev.starling.fanout;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Drains the outbox and fans tweets out to follower timelines. */
@SpringBootApplication
public class FanoutWorkerApplication {

  public static void main(String[] args) {
    SpringApplication.run(FanoutWorkerApplication.class, args);
  }
}
