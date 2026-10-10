package com.flyvo.socialcore.media.infrastructure.storage;

import com.flyvo.socialcore.media.application.MediaLimits;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MediaLimits.class)
class MediaLimitsConfiguration {}
