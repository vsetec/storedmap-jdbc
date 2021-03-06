/*
 * Copyright 2018 Fyodor Kravchenko {@literal(<fedd@vsetec.com>)}.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.storedmap.jdbc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import org.apache.commons.dbcp.BasicDataSource;
import org.mvel2.templates.CompiledTemplate;
import org.mvel2.templates.TemplateCompiler;
import org.mvel2.templates.TemplateRuntime;
import org.storedmap.Driver;
import org.storedmap.StoredMapException;

/**
 *
 * @author Fyodor Kravchenko {@literal(<fedd@vsetec.com>)}
 */
public abstract class AbstractJdbcDriver implements Driver<BasicDataSource> {

    //private final Base32 _b32 = new Base32(true);
    private final ObjectMapper _om = new ObjectMapper();

    {
        _om.configure(SerializationFeature.INDENT_OUTPUT, true);
    }
    private final Map<BasicDataSource, Set<String>> _indices = new HashMap<>();
    private final Map<String, Map<String, Object>> _mvelContext = new HashMap<>();
    private final Map<String, CompiledTemplate> _dynamicSql = new HashMap<>();
    private final Map<String, Map<String, String>> _indexStaticSql = new HashMap<>();
    //private final Map<BasicDataSource, ExecutorService> _indexers = new HashMap<>();

    @Override
    public BasicDataSource openConnection(Properties properties) {

        Properties sqlProps = new Properties();
        try {
            sqlProps.load(this.getClass().getResourceAsStream("queries.properties"));
        } catch (IOException e) {
            throw new RuntimeException("Couldn't initialize driver", e);
        }

        _dynamicSql.clear();
        _indexStaticSql.clear();

        // override default query templates with those out of connection properties
        for (Map.Entry entry : properties.entrySet()) {
            String propertyName = (String) entry.getKey();
            if (propertyName.startsWith("jdbc.queries.")) {
                propertyName = propertyName.substring("jdbc.queries.".length());

                sqlProps.setProperty(propertyName, (String) entry.getValue());
            }
        }

        for (String queryName : sqlProps.stringPropertyNames()) {
            String sqlTemplate = sqlProps.getProperty(queryName);
            CompiledTemplate ct = TemplateCompiler.compileTemplate(sqlTemplate);
            _dynamicSql.put(queryName, ct);
            _indexStaticSql.put(queryName, new HashMap<>());
        }

        BasicDataSource ds = new BasicDataSource();

        for (Map.Entry entry : properties.entrySet()) {
            String propertyName = (String) entry.getKey();
            if (propertyName.startsWith("jdbc.")) {
                if (propertyName.startsWith("jdbc.queries.")) {
                    continue;
                }
                if (propertyName.startsWith("jdbc.pool.")) {
                    continue;
                }
                propertyName = propertyName.substring("jdbc.".length());
                if (propertyName.equals("url")
                        || propertyName.equals("driver")
                        || propertyName.equals("user")
                        || propertyName.equals("password")
                        || propertyName.startsWith("queries.")) {
                    continue;
                }
            }
            ds.addConnectionProperty(propertyName, (String) entry.getValue());
        }

        // TODO: parametrize other interesting pooling and non pooling options
        ds.setMaxActive(Integer.parseInt(properties.getProperty("jdbc.pool.maxActive", "90")));
        ds.setMaxIdle(Integer.parseInt(properties.getProperty("jdbc.pool.maxIdle", "50")));
        ds.setMinIdle(Integer.parseInt(properties.getProperty("jdbc.pool.minIdle", "10")));
        ds.setInitialSize(Integer.parseInt(properties.getProperty("jdbc.pool.initialSize", "10")));
        ds.setPoolPreparedStatements(Boolean.parseBoolean(properties.getProperty("jdbc.pool.poolPreparedStatements", "true")));

        ds.setUrl(properties.getProperty("jdbc.url"));
        ds.setDriverClassName(properties.getProperty("jdbc.driver"));
        String user = properties.getProperty("jdbc.user");
        if (user != null) {
            ds.setUsername(user);
        }
        String pwd = properties.getProperty("jdbc.password");
        if (pwd != null) {
            ds.setPassword(pwd);
        }

        ds.setDefaultTransactionIsolation(Connection.TRANSACTION_READ_UNCOMMITTED);
        ds.setDefaultAutoCommit(false);

        _indices.put(ds, new HashSet<>());
        //_indexers.put(ds, Executors.newCachedThreadPool((Runnable r) -> new Thread(r, "StoredMapJdbcIndexer-" + (int) (Math.random() * 999))));

        //TODO: implement a single thread very old lock sweeper - remove locks that are a month old from time to time like once a week
        return ds;
    }

    @Override
    public void closeConnection(BasicDataSource ds) {
        try {

            //ExecutorService indexer = _indexers.remove(ds);
            //indexer.shutdown();
            //try {
            //    indexer.awaitTermination(2, TimeUnit.MINUTES);
            //} catch (InterruptedException e) {
            //    throw new RuntimeException("Unexpected interruption", e);
            //}
            _indices.remove(ds);
            ds.close();
        } catch (SQLException e) {
            throw new StoredMapException("Couldn'c close the connection", e);
        }
    }

    @Override
    public Iterable<String> getIndices(BasicDataSource connection) {
        try {
            HashSet<String> tables = new HashSet<>();
            Connection conn = connection.getConnection();
            //System.out.println("Got connection for metadata");
            DatabaseMetaData md = conn.getMetaData();
            ResultSet rs = md.getTables(null, null, "%", null);
            while (rs.next()) {
                String indexCandidate = rs.getString(3).toLowerCase();
                int strPos = -1;
                if ((strPos = indexCandidate.indexOf("_main")) > 0) {
                    indexCandidate = indexCandidate.substring(0, strPos);
                } else if ((strPos = indexCandidate.indexOf("_lock")) > 0) {
                    indexCandidate = indexCandidate.substring(0, strPos);
                } else if ((strPos = indexCandidate.indexOf("_indx")) > 0) {
                    indexCandidate = indexCandidate.substring(0, strPos);
                }
                if (strPos > 0) {
                    tables.add(indexCandidate);
                }
            }
            rs.close();
            conn.close();
            // System.out.println("Closed connection for metadata");
            return tables;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private Connection _getSqlConnection(BasicDataSource connection, String table) throws SQLException {
        try {
            synchronized (this) {

                Set<String> tables = _indices.get(connection);
                boolean ok = tables.contains(table);

                if (!ok) {
                    Map<String, String> vars = new HashMap<>(3);
                    vars.put("indexName", table);
                    _mvelContext.put(table, Collections.unmodifiableMap(vars));
                    String allSqls = (String) TemplateRuntime.execute(_dynamicSql.get("create"), vars);
                    String[] sqls = allSqls.split(";");
                    String checkSql = (String) TemplateRuntime.execute(_dynamicSql.get("check"), vars);

                    Connection conn = connection.getConnection();
                    //System.out.println("Got connection for create table");
                    Statement s = conn.createStatement();
                    try {
                        s.executeQuery(checkSql); // dumb test for table existence
                        s.close();
                    } catch (SQLException e) {
                        s.clearWarnings();
                        s.close();
                        conn.rollback();
                        for (String sql : sqls) {
                            Statement st = conn.createStatement();
                            st.executeUpdate(sql);
                            st.close();
                        }
                        conn.commit();
                    }
                    tables.add(table);
                    return conn;
                }
            }

            //System.out.println("Preparing to get just a connection");
            return connection.getConnection();

        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void put(
            String key,
            String indexName,
            BasicDataSource ds,
            byte[] value,
            Runnable callbackBeforeIndex,
            Runnable callbackAfterIndex) {

        try { // TODO: convert all to try with resources

            if (callbackBeforeIndex != null) {
                callbackBeforeIndex.run();
            }

            Connection conn = _getSqlConnection(ds, indexName);

            // first remove all
            String sql = _getSql(indexName, "delete");
            PreparedStatement ps = conn.prepareStatement(sql);
            ps.setString(1, key);
            ps.executeUpdate();
            ps.close();

            // now insert main value
            sql = _getSql(indexName, "insert");
            ps = conn.prepareStatement(sql);
            ps.setString(1, key);
            ps.setBytes(2, value);
            ps.executeUpdate();
            //System.out.println("inserted main, key " + key);
            ps.close();

            conn.commit();
            conn.close();
            //System.out.println("Closed connection for regular put");

            // call callback
            if (callbackAfterIndex != null) {
                callbackAfterIndex.run();
            }

        } catch (SQLException e) {
            throw new RuntimeException(e);
        }

    }

    @Override
    public void put(
            String key,
            String indexName,
            BasicDataSource ds,
            Map<String, Object> map,
            Locale[] locales,
            String secondaryKey,
            byte[] sorter,
            String[] tags,
            Runnable callbackOnAdditionalIndex) {

        try { // TODO: convert all to try with resources
            Connection conn = _getSqlConnection(ds, indexName);

            String sql = _getSql(indexName, "deleteIndex");
            PreparedStatement ps = conn.prepareStatement(sql);
            ps.setString(1, key);
            ps.executeUpdate();
            ps.close();

            // now insert additional indexing data
            sql = _getSql(indexName, "insertIndex");
            ps = conn.prepareStatement(sql);
            String json = _om.writeValueAsString(map);
            for (String tag : tags) {
                ps.setString(1, key);
                ps.setString(2, json);
                ps.setString(3, tag);
                if (sorter == null) {
                    ps.setNull(4, Types.BINARY);
                } else {
                    ps.setBytes(4, sorter);
                }
                if (secondaryKey == null) {
                    ps.setNull(5, Types.VARCHAR);
                } else {
                    ps.setString(5, secondaryKey);
                }
                ps.executeUpdate();
                //System.out.println("inserted tagged indx, key " + key + ", sorter " + sorterStr + ", tag " + tag + ", json " + json);                    
            }
            ps.close();

            conn.commit();
            conn.close();
            //System.out.println("Closed connection for put indices");

            if (callbackOnAdditionalIndex != null) {
                callbackOnAdditionalIndex.run();
            }

        } catch (SQLException | JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    private String _getSql(String indexName, String queryName, Object... paramsNameValue) {

        String ret;
        Map<String, String> stat;
        if (paramsNameValue == null || paramsNameValue.length == 0) {
            stat = _indexStaticSql.get(queryName);
            synchronized (stat) {
                ret = _indexStaticSql.get(queryName).get(indexName);
            }
        } else {
            ret = null;
            stat = null;
        }

        if (ret == null) {
            CompiledTemplate ct = _dynamicSql.get(queryName);
            Map<String, Object> context = _mvelContext.get(indexName);

            if (stat != null) {
                ret = (String) TemplateRuntime.execute(ct, context);
                synchronized (stat) {
                    stat.put(indexName, ret);
                }
            } else {

                context = new HashMap<>(context);
                for (int i = 0; i < paramsNameValue.length; i++) {
                    context.put((String) paramsNameValue[i], paramsNameValue[++i]);
                }
                ret = (String) TemplateRuntime.execute(ct, context);

                if (paramsNameValue == null || paramsNameValue.length == 0) {
                    stat = _indexStaticSql.get(queryName);
                    synchronized (stat) {
                        _indexStaticSql.get(queryName).put(indexName, ret);
                    }
                }

            }
        }

        //System.out.println("RUNNING SQL: " + ret);
        return ret;

    }

    @Override
    public Lock tryLock(String key, String indexName, BasicDataSource ds, int milliseconds, String sessionId) {
        try { // TODO: convert all to try with resources
            Connection conn = _getSqlConnection(ds, indexName);
            String sql = _getSql(indexName, "selectLock");
            PreparedStatement ps = conn.prepareStatement(sql);
            ps.setString(1, key);
            ResultSet rs = ps.executeQuery();

            int millisStillToWait;
            String waiterId;

            Timestamp currentTime;
            if (rs.next()) {
                currentTime = rs.getTimestamp(1);
                Timestamp createdat = rs.getTimestamp(2);
                int waitfor = rs.getInt(3);
                waiterId = rs.getString(4);
                millisStillToWait = (int) (createdat.toInstant().toEpochMilli() + waitfor - currentTime.toInstant().toEpochMilli());

            } else {
                currentTime = null;
                waiterId = sessionId;
                millisStillToWait = 0;
            }
            rs.close();
            ps.close();

            // write lock time if we are not waiting anymore
            if (millisStillToWait <= 0) {

                if (currentTime == null) { // there was no lock record
                    sql = _getSql(indexName, "insertLock");
                    ps = conn.prepareStatement(sql);
                    ps.setString(1, key);
                    ps.setInt(2, milliseconds);
                    ps.setString(3, sessionId);
                    ps.executeUpdate();
                    ps.close();
                } else {
                    sql = _getSql(indexName, "updateLock");
                    ps = conn.prepareStatement(sql);
                    ps.setInt(1, milliseconds);
                    ps.setString(2, sessionId);
                    ps.setString(3, key);
                    ps.executeUpdate();
                    ps.close();
                }

                conn.commit();

            }

            conn.close();
            //System.out.println("Closed connection for lock");

            return new Lock() {
                @Override
                public int getWaitTime() {
                    return millisStillToWait;
                }

                @Override
                public String getLockerSession() {
                    return waiterId;
                }
            };

        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void unlock(String key, String indexName, BasicDataSource ds) {
        try { // TODO: convert all to try with resources
            Connection conn = _getSqlConnection(ds, indexName);
            String sql = _getSql(indexName, "deleteLock");
            PreparedStatement ps = conn.prepareStatement(sql);
            ps.setString(1, key);
            int deleted = ps.executeUpdate();
            ps.close();
            if (deleted > 0) {
                conn.commit();
            }
            conn.close();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public byte[] get(String key, String indexName, BasicDataSource ds) {
        try { // TODO: convert all to try with resources
            Connection conn = _getSqlConnection(ds, indexName);

            PreparedStatement ps = conn.prepareStatement(_getSql(indexName, "selectById"));
            ps.setString(1, key);
            ResultSet rs = ps.executeQuery();

            byte[] ret;
            if (rs.next()) {
                ret = rs.getBytes(1);
            } else {
                ret = null;
            }

            rs.close();
            ps.close();
            //conn.commit();
            conn.close();
            //System.out.println("Closed connection for UNlock");

            return ret;

        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Iterable<String> get(String indexName, BasicDataSource ds, int from, int size) {
        try { // TODO: convert all to try with resources
            Connection conn = _getSqlConnection(ds, indexName);

            PreparedStatement ps = conn.prepareStatement(_getSql(indexName, "selectAll"));

            ResultIterable ri = new ResultIterable(conn, ps, from, size);

            return ri;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private Iterable<String> _getByTags(String indexName, BasicDataSource ds, String[] anyOfTags, Boolean ascending, int from, int size) {
        try { // TODO: convert all to try with resources
            Connection conn = _getSqlConnection(ds, indexName);
            PreparedStatement ps = conn.prepareStatement(_getSql(indexName, "selectByTags", "tags", anyOfTags, "ascending", ascending));

            for (int i = 0; i < anyOfTags.length; i++) {
                ps.setString(i + 1, anyOfTags[i]);
            }

            ResultIterable ri = new ResultIterable(conn, ps, from, size);

            return ri;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private Iterable<String> _getBySec(String indexName, BasicDataSource ds, String sec, Boolean ascending, int from, int size) {
        try { // TODO: convert all to try with resources
            Connection conn = _getSqlConnection(ds, indexName);
            PreparedStatement ps = conn.prepareStatement(_getSql(indexName, "selectBySec", "sec", sec, "ascending", ascending));
            ps.setString(1, sec);
            ResultIterable ri = new ResultIterable(conn, ps, from, size);

            return ri;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private Iterable<String> _getFilteredSorted(String indexName, BasicDataSource ds, byte[] minSorter, byte[] maxSorter, Boolean ascending, int from, int size) {
        try { // TODO: convert all to try with resources
            Connection conn = _getSqlConnection(ds, indexName);

            PreparedStatement ps = conn.prepareStatement(_getSql(indexName, "selectFilterSorted", "minSorter", minSorter, "maxSorter", maxSorter, "ascending", ascending));
            int i = 1;
            if (minSorter != null) {
                ps.setBytes(i, minSorter);
                i++;
            }
            if (maxSorter != null) {
                ps.setBytes(i, maxSorter);
                i++;
            }
            ResultIterable ri = new ResultIterable(conn, ps, from, size);

            return ri;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private Iterable<String> _getByTagsAndFilteredSorted(String indexName, BasicDataSource ds, byte[] minSorter, byte[] maxSorter, String[] anyOfTags, Boolean ascending, int from, int size) {
        try { // TODO: convert all to try with resources
            Connection conn = _getSqlConnection(ds, indexName);

            PreparedStatement ps = conn.prepareStatement(_getSql(indexName, "selectByTagsAndFilterSorted", "tags", anyOfTags, "minSorter", minSorter, "maxSorter", maxSorter, "ascending", ascending));
            int i = 1;
            if (minSorter != null) {
                ps.setBytes(i, minSorter);
                i++;
            }
            if (maxSorter != null) {
                ps.setBytes(i, maxSorter);
                i++;
            }

            for (String tag : anyOfTags) {
                ps.setString(i, tag);
                i++;
            }

            ResultIterable ri = new ResultIterable(conn, ps, from, size);

            return ri;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private Iterable<String> _getByTagsAndSec(String indexName, BasicDataSource ds, String[] anyOfTags, String sec, Boolean ascending, int from, int size) {
        try { // TODO: convert all to try with resources
            Connection conn = _getSqlConnection(ds, indexName);
            PreparedStatement ps = conn.prepareStatement(_getSql(indexName, "selectByTagsAndSec", "tags", anyOfTags, "sec", sec, "ascending", ascending));

            int i = 0;
            for (; i < anyOfTags.length; i++) {
                ps.setString(i + 1, anyOfTags[i]);
            }
            ps.setString(i + 1, sec);

            ResultIterable ri = new ResultIterable(conn, ps, from, size);

            return ri;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private Iterable<String> _getBySecAndFilteredSorted(String indexName, BasicDataSource ds, byte[] minSorter, byte[] maxSorter, String sec, Boolean ascending, int from, int size) {
        try { // TODO: convert all to try with resources
            Connection conn = _getSqlConnection(ds, indexName);

            PreparedStatement ps = conn.prepareStatement(_getSql(indexName, "selectBySecAndFilterSorted", "minSorter", minSorter, "maxSorter", maxSorter, "ascending", ascending, "sec", sec));
            int i = 1;
            if (minSorter != null) {
                ps.setBytes(i, minSorter);
                i++;
            }
            if (maxSorter != null) {
                ps.setBytes(i, maxSorter);
                i++;
            }
            ps.setString(i, sec);
            ResultIterable ri = new ResultIterable(conn, ps, from, size);

            return ri;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private Iterable<String> _getByTagsAndSecAndFilteredSorted(String indexName, BasicDataSource ds, byte[] minSorter, byte[] maxSorter, String[] anyOfTags, String sec, Boolean ascending, int from, int size) {
        try { // TODO: convert all to try with resources
            Connection conn = _getSqlConnection(ds, indexName);

            PreparedStatement ps = conn.prepareStatement(_getSql(indexName, "selectByTagsAndSecAndFilterSorted", "tags", anyOfTags, "minSorter", minSorter, "maxSorter", maxSorter, "ascending", ascending, "sec", sec));
            int i = 1;
            if (minSorter != null) {
                ps.setBytes(i, minSorter);
                i++;
            }
            if (maxSorter != null) {
                ps.setBytes(i, maxSorter);
                i++;
            }

            for (String tag : anyOfTags) {
                ps.setString(i, tag);
                i++;
            }
            ps.setString(i, sec);

            ResultIterable ri = new ResultIterable(conn, ps, from, size);

            return ri;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void remove(String key, String indexName, BasicDataSource ds, Runnable callback) {
        try { // TODO: convert all to try with resources
            Connection conn = _getSqlConnection(ds, indexName);

            String sql = _getSql(indexName, "delete");
            PreparedStatement ps = conn.prepareStatement(sql);
            ps.setString(1, key);
            int deleted = ps.executeUpdate();
            ps.close();

            sql = _getSql(indexName, "deleteIndex");
            ps = conn.prepareStatement(sql);
            ps.setString(1, key);
            deleted += ps.executeUpdate();
            ps.close();

            if (deleted > 0) {
                conn.commit();
            }
            conn.close();
            //System.out.println("Closed connection for remove one");

            if (callback != null) {
                callback.run();
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void removeAll(String indexName, BasicDataSource connection) {
        try { // TODO: convert all to try with resources
            Connection conn = _getSqlConnection(connection, indexName);

            String[] allSqls = _getSql(indexName, "deleteAll").split(";");

            PreparedStatement ps = conn.prepareStatement(allSqls[0]);
            ps.executeUpdate();
            ps.close();

            ps = conn.prepareStatement(allSqls[1]);
            ps.executeUpdate();
            ps.close();

            conn.commit();
            conn.close();
            //System.out.println("Closed connection for remove all");

        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Iterable<String> get(String indexName, BasicDataSource connection, String secondaryKey, byte[] minSorter, byte[] maxSorter, String[] anyOfTags, Boolean ascending, String textQuery) {
        return get(indexName, connection, secondaryKey, minSorter, maxSorter, anyOfTags, ascending, textQuery, 0, Integer.MAX_VALUE);
    }

    @Override
    public Iterable<String> get(String indexName, BasicDataSource connection, String secondaryKey, byte[] minSorter, byte[] maxSorter, String[] anyOfTags, Boolean ascending, String textQuery, int from, int size) {
        if (textQuery != null) {
            return getWithQuery(indexName, connection, secondaryKey, minSorter, maxSorter, anyOfTags, ascending, textQuery);
        }

        if (secondaryKey != null) {
            if (minSorter != null || maxSorter != null) {
                if (anyOfTags != null) {
                    return _getByTagsAndSecAndFilteredSorted(indexName, connection, minSorter, maxSorter, anyOfTags, secondaryKey, ascending, from, size);
                } else {
                    return _getBySecAndFilteredSorted(indexName, connection, minSorter, maxSorter, secondaryKey, ascending, from, size);
                }
            } else {
                if (anyOfTags != null) {
                    return _getByTagsAndSec(indexName, connection, anyOfTags, secondaryKey, ascending, from, size);
                } else {
                    return _getBySec(indexName, connection, secondaryKey, ascending, from, size);
                }
            }
        } else {
            if (minSorter != null || maxSorter != null) {
                if (anyOfTags != null) {
                    return _getByTagsAndFilteredSorted(indexName, connection, minSorter, maxSorter, anyOfTags, ascending, from, size);
                } else {
                    return _getFilteredSorted(indexName, connection, minSorter, maxSorter, ascending, from, size);
                }
            } else {
                if (anyOfTags != null) {
                    return _getByTags(indexName, connection, anyOfTags, ascending, from, size);
                } else {
                    return get(indexName, connection, from, size);
                }
            }
        }
    }

    @Override
    public Iterable<String> get(String indexName, BasicDataSource connection) {
        return get(indexName, connection, 0, Integer.MAX_VALUE);
    }

    @Override
    public long count(String indexName, BasicDataSource ds) {
        try { // TODO: convert all to try with resources
            Connection conn = _getSqlConnection(ds, indexName);

            PreparedStatement ps = conn.prepareStatement(_getSql(indexName, "countAll"));
            ResultSet rs = ps.executeQuery();
            rs.next();
            long ret = rs.getLong(1);
            rs.close();
            conn.close();
            //System.out.println("Closed connection for count");
            return ret;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private long _countByTags(String indexName, BasicDataSource ds, String[] anyOfTags) {
        try { // TODO: convert all to try with resources
            Connection conn = _getSqlConnection(ds, indexName);
            PreparedStatement ps = conn.prepareStatement(_getSql(indexName, "countByTags", "tags", anyOfTags));

            for (int i = 0; i < anyOfTags.length; i++) {
                ps.setString(i + 1, anyOfTags[i]);
            }

            ResultSet rs = ps.executeQuery();
            rs.next();
            long ret = rs.getLong(1);
            rs.close();
            conn.close();
            //System.out.println("Closed connection for count");
            return ret;

        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private long _countFiltered(String indexName, BasicDataSource ds, byte[] minSorter, byte[] maxSorter) {
        try { // TODO: convert all to try with resources
            Connection conn = _getSqlConnection(ds, indexName);

            PreparedStatement ps = conn.prepareStatement(_getSql(indexName, "countFiltered", "minSorter", minSorter, "maxSorter", maxSorter));
            int i = 1;
            if (minSorter != null) {
                ps.setBytes(i, minSorter);
                i++;
            }
            if (maxSorter != null) {
                ps.setBytes(i, maxSorter);
                i++;
            }
            ResultSet rs = ps.executeQuery();
            rs.next();
            long ret = rs.getLong(1);
            rs.close();
            conn.close();
            //System.out.println("Closed connection for count");
            return ret;

        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private long _countByTagsAndFiltered(String indexName, BasicDataSource ds, byte[] minSorter, byte[] maxSorter, String[] anyOfTags) {
        try { // TODO: convert all to try with resources
            Connection conn = _getSqlConnection(ds, indexName);

            PreparedStatement ps = conn.prepareStatement(_getSql(indexName, "countByTagsAndFiltered", "tags", anyOfTags, "minSorter", minSorter, "maxSorter", maxSorter));
            int i = 1;
            if (minSorter != null) {
                ps.setBytes(i, minSorter);
                i++;
            }
            if (maxSorter != null) {
                ps.setBytes(i, maxSorter);
                i++;
            }

            for (String tag : anyOfTags) {
                ps.setString(i, tag);
                i++;
            }

            ResultSet rs = ps.executeQuery();
            rs.next();
            long ret = rs.getLong(1);
            rs.close();
            conn.close();
            //System.out.println("Closed connection for count");
            return ret;

        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private long _countBySec(String indexName, BasicDataSource ds, String sec) {
        try { // TODO: convert all to try with resources
            Connection conn = _getSqlConnection(ds, indexName);
            PreparedStatement ps = conn.prepareStatement(_getSql(indexName, "countBySec", "sec", sec));
            ps.setString(1, sec);
            ResultSet rs = ps.executeQuery();
            rs.next();
            long ret = rs.getLong(1);
            rs.close();
            conn.close();
            //System.out.println("Closed connection for count");
            return ret;

        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private long _countByTagsAndSec(String indexName, BasicDataSource ds, String[] anyOfTags, String sec) {
        try { // TODO: convert all to try with resources
            Connection conn = _getSqlConnection(ds, indexName);
            PreparedStatement ps = conn.prepareStatement(_getSql(indexName, "countByTagsAndSec", "tags", anyOfTags, "sec", sec));
            int i = 0;
            for (; i < anyOfTags.length; i++) {
                ps.setString(i + 1, anyOfTags[i]);
            }
            ps.setString(i + 1, sec);
            ResultSet rs = ps.executeQuery();
            rs.next();
            long ret = rs.getLong(1);
            rs.close();
            conn.close();
            //System.out.println("Closed connection for count");
            return ret;

        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private long _countBySecAndFiltered(String indexName, BasicDataSource ds, byte[] minSorter, byte[] maxSorter, String sec) {
        try { // TODO: convert all to try with resources
            Connection conn = _getSqlConnection(ds, indexName);

            PreparedStatement ps = conn.prepareStatement(_getSql(indexName, "countFiltered", "minSorter", minSorter, "maxSorter", maxSorter, "sec", sec));
            int i = 1;
            if (minSorter != null) {
                ps.setBytes(i, minSorter);
                i++;
            }
            if (maxSorter != null) {
                ps.setBytes(i, maxSorter);
                i++;
            }
            ps.setString(i, sec);
            ResultSet rs = ps.executeQuery();
            rs.next();
            long ret = rs.getLong(1);
            rs.close();
            conn.close();
            //System.out.println("Closed connection for count");
            return ret;

        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private long _countByTagsAndSecAndFiltered(String indexName, BasicDataSource ds, byte[] minSorter, byte[] maxSorter, String[] anyOfTags, String sec) {
        try { // TODO: convert all to try with resources
            Connection conn = _getSqlConnection(ds, indexName);

            PreparedStatement ps = conn.prepareStatement(_getSql(indexName, "countByTagsAndSecAndFiltered", "tags", anyOfTags, "minSorter", minSorter, "maxSorter", maxSorter, "sec", sec));
            int i = 1;
            if (minSorter != null) {
                ps.setBytes(i, minSorter);
                i++;
            }
            if (maxSorter != null) {
                ps.setBytes(i, maxSorter);
                i++;
            }

            for (String tag : anyOfTags) {
                ps.setString(i, tag);
                i++;
            }
            ps.setString(i, sec);
            ResultSet rs = ps.executeQuery();
            rs.next();
            long ret = rs.getLong(1);
            rs.close();
            conn.close();
            //System.out.println("Closed connection for count");
            return ret;

        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public long count(String indexName, BasicDataSource connection, String secondaryKey, byte[] minSorter, byte[] maxSorter, String[] anyOfTags, String textQuery) {
        if (textQuery != null) {
            return countWithQuery(indexName, connection, secondaryKey, minSorter, maxSorter, anyOfTags, textQuery);
        }

        if (secondaryKey != null) {
            if (minSorter != null || maxSorter != null) {
                if (anyOfTags != null) {
                    return _countByTagsAndSecAndFiltered(indexName, connection, minSorter, maxSorter, anyOfTags, secondaryKey);
                } else {
                    return _countBySecAndFiltered(indexName, connection, minSorter, maxSorter, secondaryKey);
                }
            } else {
                if (anyOfTags != null) {
                    return _countByTagsAndSec(indexName, connection, anyOfTags, secondaryKey);
                } else {
                    return _countBySec(indexName, connection, secondaryKey);
                }
            }
        } else {
            if (minSorter != null || maxSorter != null) {
                if (anyOfTags != null) {
                    return _countByTagsAndFiltered(indexName, connection, minSorter, maxSorter, anyOfTags);
                } else {
                    return _countFiltered(indexName, connection, minSorter, maxSorter);
                }
            } else {
                if (anyOfTags != null) {
                    return _countByTags(indexName, connection, anyOfTags);
                } else {
                    return count(indexName, connection);
                }
            }
        }
    }

    public abstract long countWithQuery(String indexName, BasicDataSource connection, String secondaryKey, byte[] minSorter, byte[] maxSorter, String[] anyOfTags, String textQuery);

    public abstract Iterable<String> getWithQuery(String indexName, BasicDataSource connection, String secondaryKey, byte[] minSorter, byte[] maxSorter, String[] anyOfTags, Boolean ascending, String textQuery);
}
