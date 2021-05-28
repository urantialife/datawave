package datawave.microservice.common.storage;

import com.fasterxml.jackson.annotation.JsonIgnore;
import datawave.webservice.query.Query;
import org.apache.accumulo.core.security.Authorizations;
import org.apache.commons.lang3.builder.EqualsBuilder;
import org.apache.commons.lang3.builder.HashCodeBuilder;
import org.apache.commons.lang3.builder.ToStringBuilder;

import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Date;
import java.util.Set;
import java.util.stream.Collectors;

public class QueryStatus implements Serializable {
    public enum QUERY_STATE {
        DEFINED, CREATED, CLOSED, CANCELED, FAILED
    }
    
    private QueryKey queryKey;
    private QUERY_STATE queryState = QUERY_STATE.DEFINED;
    private Query query;
    private Set<String> calculatedAuths;
    @JsonIgnore
    private Set<Authorizations> calculatedAuthorizations;
    private String plan;
    
    private long numResultsReturned = 0L;
    private long numResultsGenerated = 0L;
    private int concurrentNextCount = 0;
    private long lastPageNumber = 0L;
    
    // datetime of last user interaction
    private Date lastUsed;
    
    // datetime of last service interaction
    private Date lastUpdated;
    
    private String failureMessage;
    private String stackTrace;
    
    public QueryStatus() {}
    
    public QueryStatus(QueryKey queryKey) {
        setQueryKey(queryKey);
    }
    
    public void setQueryKey(QueryKey key) {
        this.queryKey = key;
    }
    
    public QueryKey getQueryKey() {
        return queryKey;
    }
    
    public QUERY_STATE getQueryState() {
        return queryState;
    }
    
    public void setQueryState(QUERY_STATE queryState) {
        this.queryState = queryState;
    }
    
    public String getPlan() {
        return plan;
    }
    
    public void setPlan(String plan) {
        this.plan = plan;
    }
    
    public Query getQuery() {
        return query;
    }
    
    public void setQuery(Query query) {
        this.query = query;
    }
    
    public Set<String> getCalculatedAuths() {
        if (calculatedAuths == null && calculatedAuthorizations != null) {
            calculatedAuths = this.calculatedAuthorizations.stream().flatMap(a -> a.getAuthorizations().stream())
                            .map(b -> new String(b, StandardCharsets.UTF_8)).collect(Collectors.toSet());
        }
        return calculatedAuths;
    }
    
    public void setCalculatedAuths(Set<String> calculatedAuths) {
        this.calculatedAuths = calculatedAuths;
        this.calculatedAuthorizations = null;
        getCalculatedAuthorizations();
    }
    
    public Set<Authorizations> getCalculatedAuthorizations() {
        if (calculatedAuthorizations == null && calculatedAuths != null) {
            calculatedAuthorizations = Collections.singleton(
                            new Authorizations(this.calculatedAuths.stream().map(a -> a.getBytes(StandardCharsets.UTF_8)).collect(Collectors.toList())));
        }
        return calculatedAuthorizations;
    }
    
    public void setCalculatedAuthorizations(Set<Authorizations> calculatedAuthorizations) {
        this.calculatedAuthorizations = calculatedAuthorizations;
        this.calculatedAuths = null;
        getCalculatedAuths();
    }
    
    public String getFailureMessage() {
        return failureMessage;
    }
    
    public void setFailureMessage(String failureMessage) {
        this.failureMessage = failureMessage;
    }
    
    public String getStackTrace() {
        return stackTrace;
    }
    
    public void setStackTrace(String stackTrace) {
        this.stackTrace = stackTrace;
    }
    
    @JsonIgnore
    public void setFailure(Exception failure) {
        setFailureMessage(failure.getMessage());
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        PrintWriter writer = new PrintWriter(new OutputStreamWriter(outputStream, StandardCharsets.UTF_8));
        failure.printStackTrace(writer);
        setStackTrace(new String(outputStream.toByteArray(), StandardCharsets.UTF_8));
    }
    
    public long getNumResultsReturned() {
        return numResultsReturned;
    }
    
    public void setNumResultsReturned(long numResultsReturned) {
        this.numResultsReturned = numResultsReturned;
    }
    
    public void incrementNumResultsReturned(long increment) {
        this.numResultsReturned += increment;
    }
    
    public long getNumResultsGenerated() {
        return numResultsGenerated;
    }
    
    public void setNumResultsGenerated(long numResultsGenerated) {
        this.numResultsGenerated = numResultsGenerated;
    }
    
    public void incrementNumResultsGenerated(long increment) {
        this.numResultsGenerated += increment;
    }
    
    public int getConcurrentNextCount() {
        return concurrentNextCount;
    }
    
    public void setConcurrentNextCount(int concurrentNextCount) {
        this.concurrentNextCount = concurrentNextCount;
    }
    
    public long getLastPageNumber() {
        return lastPageNumber;
    }
    
    public void setLastPageNumber(long lastPageNumber) {
        this.lastPageNumber = lastPageNumber;
    }
    
    public Date getLastUsed() {
        return lastUsed;
    }
    
    public void setLastUsed(Date lastUsed) {
        this.lastUsed = lastUsed;
    }
    
    public Date getLastUpdated() {
        return lastUpdated;
    }
    
    public void setLastUpdated(Date lastUpdated) {
        this.lastUpdated = lastUpdated;
    }
    
    @Override
    public int hashCode() {
        // @formatter:off
        return new HashCodeBuilder()
                .append(queryKey)
                .append(queryState)
                .append(query)
                .append(calculatedAuths)
                .append(calculatedAuthorizations)
                .append(plan)
                .append(numResultsReturned)
                .append(numResultsGenerated)
                .append(concurrentNextCount)
                .append(lastPageNumber)
                .append(lastUsed)
                .append(lastUpdated)
                .append(failureMessage)
                .append(stackTrace)
                .build();
        // @formatter:on
    }
    
    @Override
    public boolean equals(Object obj) {
        if (obj instanceof QueryStatus) {
            QueryStatus other = (QueryStatus) obj;
            // @formatter:off
            return new EqualsBuilder()
                    .append(queryKey, other.queryKey)
                    .append(queryState, other.queryState)
                    .append(query, other.query)
                    .append(calculatedAuths, other.calculatedAuths)
                    .append(calculatedAuthorizations, other.calculatedAuthorizations)
                    .append(plan, other.plan)
                    .append(numResultsReturned, other.numResultsReturned)
                    .append(numResultsGenerated, other.numResultsGenerated)
                    .append(concurrentNextCount, other.concurrentNextCount)
                    .append(lastPageNumber, other.lastPageNumber)
                    .append(lastUsed, other.lastUsed)
                    .append(lastUpdated, other.lastUpdated)
                    .append(failureMessage, other.failureMessage)
                    .append(stackTrace, other.stackTrace)
                    .build();
            // @formatter:on
        }
        return false;
    }
    
    @Override
    public String toString() {
        // @formatter:off
        return new ToStringBuilder(this)
                .append("queryKey", queryKey)
                .append("queryState", queryState)
                .append("query", query)
                .append("calculatedAuths", calculatedAuths)
                .append("calculatedAuthorizations", calculatedAuthorizations)
                .append("plan", plan)
                .append("numResultsReturned", numResultsReturned)
                .append("numResultsGenerated", numResultsGenerated)
                .append("concurrentNextCount", concurrentNextCount)
                .append("lastPageNumber", lastPageNumber)
                .append("lastUsed", lastUsed)
                .append("lastUpdated", lastUpdated)
                .append("failureMessage", failureMessage)
                .append("stackTrace", stackTrace)
                .build();
        // @formatter:on
    }
}