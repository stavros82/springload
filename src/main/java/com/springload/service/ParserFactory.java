package com.springload.service;

import com.springload.strategy.ParserType;
import com.springload.strategy.StressConfigParserStrategy;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ParserFactory {

    private final Map<ParserType, StressConfigParserStrategy> strategies;

    public ParserFactory(List<StressConfigParserStrategy> strategyList) {
        this.strategies = strategyList.stream()
                .collect(Collectors.toMap(StressConfigParserStrategy::getType, Function.identity()));
    }

    public StressConfigParserStrategy getStrategy(ParserType type) {
        StressConfigParserStrategy strategy = strategies.get(type);
        if (strategy == null) {
            throw new IllegalArgumentException("Unsupported parser type: " + type);
        }
        return strategy;
    }
}