package com.mengx.server.law.agent;

import java.util.List;

/** 出题官客观题组输出（逐题生成时组内只放 1 题） */
public record GenOutput(List<GenQuestion> questions) {
}
