package com.songrush.audio;

import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

import com.songrush.AudioProperties;

// Matches when songrush.audio.bucket is set and not blank (S3 mode). @ConditionalOnProperty is not
// used because it treats a blank value as set; blank must mean disk mode (cycle 074, review M5).
// The rule is AudioProperties.s3Mode() itself, so the condition and the bound properties agree.
class S3ModeCondition implements Condition {

	@Override
	public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
		return Binder.get(context.getEnvironment())
				.bind("songrush.audio", AudioProperties.class)
				.map(AudioProperties::s3Mode)
				.orElse(false);
	}

}
