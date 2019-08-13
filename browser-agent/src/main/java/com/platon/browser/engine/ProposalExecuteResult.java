package com.platon.browser.engine;

import com.platon.browser.dao.entity.Proposal;
import com.platon.browser.dao.entity.Vote;
import lombok.Data;

import java.util.*;

/**
 * @Auther: Chendongming
 * @Date: 2019/8/10 16:47
 * @Description:
 */
@Data
public class ProposalExecuteResult {
    // 插入或更新数据
    private List<Vote> addVotes = new ArrayList<>();
    private Set<Proposal> addProposals = new HashSet<>();
    private Set<Proposal> updateProposals = new HashSet<>();
}