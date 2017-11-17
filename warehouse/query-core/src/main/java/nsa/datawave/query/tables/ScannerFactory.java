package nsa.datawave.query.tables;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import nsa.datawave.ingest.data.config.ingest.AccumuloHelper;
import nsa.datawave.mr.bulk.BulkInputFormat;
import nsa.datawave.mr.bulk.MultiRfileInputformat;
import nsa.datawave.mr.bulk.RfileScanner;
import nsa.datawave.query.rewrite.config.RefactoredShardQueryConfiguration;
import nsa.datawave.query.tables.stats.ScanSessionStats;
import nsa.datawave.query.util.QueryScannerHelper;
import nsa.datawave.webservice.common.connection.WrappedConnector;
import nsa.datawave.webservice.query.Query;
import nsa.datawave.webservice.query.configuration.GenericQueryConfiguration;

import org.apache.accumulo.core.Constants;
import org.apache.accumulo.core.client.BatchScanner;
import org.apache.accumulo.core.client.Connector;
import org.apache.accumulo.core.client.Scanner;
import org.apache.accumulo.core.client.ScannerBase;
import org.apache.accumulo.core.client.TableNotFoundException;
import org.apache.accumulo.core.security.Authorizations;
import org.apache.hadoop.conf.Configuration;
import org.apache.log4j.Logger;

import com.google.common.base.Preconditions;

/**
 * 
 */
public class ScannerFactory {
    
    protected int maxQueue = 1000;
    protected HashSet<ScannerBase> instances = new HashSet<>();
    protected HashSet<ScannerSession> sessionInstances = new HashSet<>();
    protected Connector cxn;
    protected boolean open = true;
    protected boolean accrueStats = false;
    protected Query settings;
    protected ResourceQueue scanQueue = null;
    RefactoredShardQueryConfiguration config = null;
    
    private static final Logger log = Logger.getLogger(ScannerFactory.class);
    
    public ScannerFactory(GenericQueryConfiguration queryConfiguration) {
        
        this.cxn = queryConfiguration.getConnector();
        
        if (queryConfiguration instanceof RefactoredShardQueryConfiguration) {
            this.settings = ((RefactoredShardQueryConfiguration) queryConfiguration).getQuery();
            this.accrueStats = ((RefactoredShardQueryConfiguration) queryConfiguration).getAccrueStats();
        }
        log.debug("Created scanner factory " + System.identityHashCode(this) + " is wrapped ? " + (cxn instanceof WrappedConnector));
        
        if (queryConfiguration instanceof RefactoredShardQueryConfiguration) {
            config = ((RefactoredShardQueryConfiguration) queryConfiguration);
            maxQueue = ((RefactoredShardQueryConfiguration) queryConfiguration).getMaxScannerBatchSize();
            this.settings = ((RefactoredShardQueryConfiguration) queryConfiguration).getQuery();
            try {
                scanQueue = new ResourceQueue(((RefactoredShardQueryConfiguration) queryConfiguration).getNumQueryThreads(), this.cxn);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }
    
    public ScannerFactory(Connector cxn) {
        this(cxn, 100);
        
    }
    
    public ScannerFactory(Connector connector, int queueSize) {
        try {
            this.cxn = connector;
            scanQueue = new ResourceQueue(queueSize, connector);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
    
    public synchronized Scanner newSingleScanner(String tableName, Set<Authorizations> auths, Query query) throws TableNotFoundException {
        if (open) {
            Scanner bs = QueryScannerHelper.createScannerWithoutInfo(cxn, tableName, auths, query);
            log.debug("Created scanner " + System.identityHashCode(bs));
            if (log.isTraceEnabled()) {
                log.trace("Adding instance " + bs.hashCode());
            }
            
            return bs;
        } else {
            throw new IllegalStateException("Factory has been locked. No new scanners can be created.");
        }
    }
    
    public synchronized BatchScanner newScanner(String tableName, Set<Authorizations> auths, int threads, Query query) throws TableNotFoundException {
        if (open) {
            BatchScanner bs = QueryScannerHelper.createBatchScanner(cxn, tableName, auths, threads, query);
            log.debug("Created scanner " + System.identityHashCode(bs));
            if (log.isTraceEnabled()) {
                log.trace("Adding instance " + bs.hashCode());
            }
            instances.add(bs);
            return bs;
        } else {
            throw new IllegalStateException("Factory has been locked. No new scanners can be created.");
        }
    }
    
    public synchronized BatchScanner newScanner(String tableName, Set<Authorizations> auths, int threads, Query query, boolean reportErrors)
                    throws TableNotFoundException {
        if (open) {
            BatchScanner bs = QueryScannerHelper.createBatchScanner(cxn, tableName, auths, threads, query, reportErrors);
            log.debug("Created scanner " + System.identityHashCode(bs));
            if (log.isTraceEnabled()) {
                log.trace("Adding instance " + bs.hashCode());
            }
            instances.add(bs);
            return bs;
        } else {
            throw new IllegalStateException("Factory has been locked. No new scanners can be created.");
        }
    }
    
    public BatchScanner newScanner(String tableName, Set<Authorizations> auths, Query query) throws TableNotFoundException {
        return newScanner(tableName, auths, 1, query);
    }
    
    public BatchScanner newScanner(String tableName, Query query) throws TableNotFoundException {
        return newScanner(tableName, Collections.singleton(Constants.NO_AUTHS), query);
    }
    
    /**
     * Builds a new scanner session using a finalized table name and set of authorizations using the previously defined queue. Note that the number of entries
     * is hardcoded, below, to 1000, but can be changed
     * 
     * @param tableName
     * @param auths
     * @return
     * @throws Exception
     */
    public synchronized BatchScannerSession newQueryScanner(final String tableName, final Set<Authorizations> auths, Query settings) throws Exception {
        
        return newLimitedScanner(BatchScannerSession.class, tableName, auths, settings).setThreads(scanQueue.getCapacity());
    }
    
    /**
     * Builds a new scanner session using a finalized table name and set of authorizations using the previously defined queue. Note that the number of entries
     * is hardcoded, below, to 1000, but can be changed
     * 
     * @param tableName
     * @param auths
     * @return
     * @throws Exception
     */
    public synchronized <T extends ScannerSession> T newLimitedScanner(Class<T> wrapper, final String tableName, final Set<Authorizations> auths,
                    final Query settings) throws Exception {
        Preconditions.checkNotNull(scanQueue);
        Preconditions.checkNotNull(wrapper);
        Preconditions.checkArgument(open, "Factory has been locked. No New scanners can be created");
        
        log.debug("Creating limited scanner whose max threads is is " + scanQueue.getCapacity() + " and max capacity is " + maxQueue);
        
        ScanSessionStats stats = null;
        if (accrueStats) {
            stats = new ScanSessionStats();
        }
        
        T session = null;
        if (wrapper == ScannerSession.class) {
            session = (T) new ScannerSession(tableName, auths, scanQueue, maxQueue, settings).applyStats(stats);
        } else {
            session = wrapper.getConstructor(ScannerSession.class).newInstance(
                            new ScannerSession(tableName, auths, scanQueue, maxQueue, settings).applyStats(stats));
        }
        
        log.debug("Created session " + System.identityHashCode(session));
        if (log.isTraceEnabled()) {
            log.trace("Adding instance " + session.hashCode());
        }
        sessionInstances.add(session);
        
        return session;
    }
    
    /**
     * Builds a new scanner session using a finalized table name and set of authorizations using the previously defined queue. Note that the number of entries
     * is hardcoded, below, to 1000, but can be changed
     * 
     * @param tableName
     * @param auths
     * @return
     * @throws Exception
     */
    public synchronized RangeStreamScanner newRangeScanner(final String tableName, final Set<Authorizations> auths, final Query settings) throws Exception {
        return newRangeScanner(tableName, auths, settings, Integer.MAX_VALUE);
    }
    
    public RangeStreamScanner newRangeScanner(String tableName, Set<Authorizations> auths, Query query, int shardsPerDayThreshold) throws Exception {
        return newLimitedScanner(RangeStreamScanner.class, tableName, auths, settings).setShardsPerDayThreshold(shardsPerDayThreshold).setScannerFactory(this);
    }
    
    public RangeStreamScanner newCondensedRangeScanner(String tableName, Set<Authorizations> auths, Query query, int shardsPerDayThreshold) throws Exception {
        return newLimitedScanner(CondensedRangeStreamScanner.class, tableName, auths, settings).setShardsPerDayThreshold(shardsPerDayThreshold)
                        .setScannerFactory(this);
    }
    
    public synchronized boolean close(ScannerBase bs) {
        boolean removed = instances.remove(bs);
        if (removed) {
            log.debug("Closed scanner " + System.identityHashCode(bs));
            if (log.isTraceEnabled()) {
                log.trace("Closing instance " + bs.hashCode());
            }
            bs.close();
        }
        return removed;
    }
    
    public synchronized Collection<ScannerBase> currentScanners() {
        return Collections.unmodifiableSet(instances);
    }
    
    public synchronized Collection<ScannerSession> currentSessions() {
        return Collections.unmodifiableSet(sessionInstances);
    }
    
    public synchronized boolean lockdown() {
        log.debug("Locked scanner factory " + System.identityHashCode(this));
        if (log.isTraceEnabled()) {
            log.trace("Locked down with following stacktrace", new Exception("stacktrace for debugging"));
        }
        
        open = false;
        return open;
    }
    
    /**
     * @param bs
     */
    public void close(ScannerSession bs) {
        try {
            log.debug("Closed session " + System.identityHashCode(bs));
            sessionInstances.remove(bs);
            if (log.isTraceEnabled()) {
                log.trace("Closing instance " + bs.hashCode());
            }
            bs.close();
        } catch (Exception e) {
            // ANY EXCEPTION HERE CAN SAFELY BE IGNORED
            e.printStackTrace();
        }
    }
    
    public void setMaxQueue(int size) {
        this.maxQueue = size;
    }
    
    public synchronized ScannerBase newRfileScanner(String tableName, Set<Authorizations> auths, Query setting) {
        Configuration conf = new Configuration();
        
        Connector con = cxn;
        
        final String instanceName = con.getInstance().getInstanceName();
        final String zookeepers = con.getInstance().getZooKeepers();
        
        AccumuloHelper.setInstanceName(conf, instanceName);
        AccumuloHelper.setUsername(conf, con.whoami());
        
        AccumuloHelper.setZooKeepers(conf, zookeepers);
        BulkInputFormat.setZooKeeperInstance(conf, instanceName, zookeepers);
        
        AccumuloHelper.setPassword(conf, config.getAccumuloPassword().getBytes());
        BulkInputFormat.setMemoryInput(conf, con.whoami(), config.getAccumuloPassword().getBytes(), tableName, auths.iterator().next());
        
        conf.set(MultiRfileInputformat.CACHE_METADATA, "true");
        
        ScannerBase baseScanner = new RfileScanner(con, conf, tableName, auths, 1);
        
        instances.add(baseScanner);
        
        return baseScanner;
    }
    
}