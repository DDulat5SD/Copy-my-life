package com.copymylife.copymylife.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

// US-28: counts the category votes of routines whose voting period has ended (CategoryVoteService).
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
