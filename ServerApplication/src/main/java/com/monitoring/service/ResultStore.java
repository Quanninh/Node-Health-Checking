package com.monitoring.service;

import com.monitoring.model.*;
import org.springframework.stereotype.Service;

@Service
public class ResultStore {

    private volatile CrackingResponse latestResult;

    public void save(CrackingResponse response) {
        this.latestResult = response;
    }

    public void clear() {
        this.latestResult = null;
    }

    public CrackingResponse getLatest() {
        return latestResult;
    }

}
