/* SPDX-License-Identifier: MIT */
package dev.starling.timeline;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Home timeline reads and timeline cache management. */
@SpringBootApplication
public class TimelineServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(TimelineServiceApplication.class, args);
  }
}
