package com.platon.browser.service;

import com.alibaba.fastjson.JSON;
import com.platon.browser.elasticsearch.dto.Block;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.DefaultTypedTuple;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.Set;

/**
 * 区块缓存数据处理逻辑
 * @Auther: Chendongming
 * @Date: 2019/8/21 09:47
 * @Description:
 */
@Slf4j
@Service
public class RedisBlockService extends RedisService<Block> {

    /** 区块缓存key */
    @Value("${spring.redis.key.blocks}")
    private String blocksCacheKey;
    @Override
    String getCacheKey() {
        return blocksCacheKey;
    }

    @Override
    void updateMinMaxScore(Set<Block> data) {
        minMax.reset();
        data.forEach(item->{
            Long score = item.getNum();
            if(score==null) return;
            if(score<minMax.getMinOffset()) minMax.setMinOffset(score);
            if(score>minMax.getMaxOffset()) minMax.setMaxOffset(score);
        });
    }

    @Override
    void updateExistScore(Set<String> exist) {
        Objects.requireNonNull(exist).forEach(item->existScore.add(JSON.parseObject(item, Block.class).getNum()));
    }

    @Override
    void updateStageSet(Set<Block> data) {
        data.forEach(item -> {
            // 在缓存中不存在的才放入缓存
            if(!existScore.contains(item.getNum())) stageSet.add(new DefaultTypedTuple(JSON.toJSONString(item),item.getNum().doubleValue()));
        });
    }
}
