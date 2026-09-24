/* SPDX-License-Identifier: MIT */
package dev.starling.tweet;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Tweets, likes, retweets, replies, media metadata and search. */
@SpringBootApplication
public class TweetServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(TweetServiceApplication.class, args);
  }
}
