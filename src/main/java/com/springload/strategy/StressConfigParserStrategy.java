package com.springload.strategy;

import com.springload.dto.StressConfig;
import java.io.InputStream;

public interface StressConfigParserStrategy {
    ParserType getType();
    StressConfig parse(InputStream inputStream);
}