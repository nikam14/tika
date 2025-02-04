/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.tika.pipes.emitter.jdbc;

import java.io.Closeable;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.tika.config.Field;
import org.apache.tika.config.Initializable;
import org.apache.tika.config.InitializableProblemHandler;
import org.apache.tika.config.Param;
import org.apache.tika.exception.TikaConfigException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.pipes.emitter.AbstractEmitter;
import org.apache.tika.pipes.emitter.EmitData;
import org.apache.tika.pipes.emitter.EmitKey;
import org.apache.tika.pipes.emitter.TikaEmitterException;
import org.apache.tika.utils.StringUtils;

/**
 * This is only an initial, basic implementation of an emitter for JDBC.
 * <p>
 * It is currently NOT thread safe because of the shared prepared statement,
 * and depending on the jdbc implementation because of the shared connection.
 * <p>
 * As of the 2.5.0 release, this is ALPHA version.  There may be breaking changes
 * in the future.
 */
public class JDBCEmitter extends AbstractEmitter implements Initializable, Closeable {

    private static final Logger LOGGER = LoggerFactory.getLogger(JDBCEmitter.class);

    public enum AttachmentStrategy {
        FIRST_ONLY, ALL
        //anything else?
    }

    public enum MultivaluedFieldStrategy {
        FIRST_ONLY, CONCATENATE
        //anything else?
    }

    //some file formats do not have time zones...
    //try both
    private static final String[] TIKA_DATE_PATTERNS =
            new String[]{"yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd'T'HH:mm:ss"};
    //the "write" lock is used for creating the table
    private static ReadWriteLock READ_WRITE_LOCK = new ReentrantReadWriteLock();
    //this keeps track of which table + connection string have been created
    //so that only one table is created per table + connection string.
    //This is necessary for testing and if someone specifies multiple
    //different jdbc emitters.
    private static Set<String> TABLES_CREATED = new HashSet<>();
    private String connectionString;

    private Optional<String> postConnectionString = Optional.empty();
    private String insert;
    private String createTable;
    private String alterTable;

    private int maxRetries = 0;

    private Map<String, String> keys;
    private Connection connection;
    private PreparedStatement insertStatement;
    private AttachmentStrategy attachmentStrategy = AttachmentStrategy.FIRST_ONLY;

    private MultivaluedFieldStrategy multivaluedFieldStrategy =
            MultivaluedFieldStrategy.CONCATENATE;

    private String multivaluedFieldDelimiter = ", ";

    //emitters are run in a single thread.  If we ever start running them
    //multithreaded, this will be a big problem.
    private final DateFormat[] dateFormats;

    public JDBCEmitter() {
        dateFormats = new DateFormat[TIKA_DATE_PATTERNS.length];
        int i = 0;
        for (String p : TIKA_DATE_PATTERNS) {
            dateFormats[i++] = new SimpleDateFormat(p, Locale.US);
        }
    }

    /**
     * This is called immediately after the table is created.
     * The purpose of this is to allow for adding a complex primary key or
     * other constraint on the table after it is created.
     *
     * @param alterTable
     */
    public void setAlterTable(String alterTable) {
        this.alterTable = alterTable;
    }

    @Field
    public void setCreateTable(String createTable) {
        this.createTable = createTable;
    }

    @Field
    public void setInsert(String insert) {
        this.insert = insert;
    }

    @Field
    public void setConnection(String connection) {
        this.connectionString = connection;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }

    /**
     * This sql will be called immediately after the connection is made. This was
     * initially added for setting pragmas on sqlite3, but may be used for other
     * connection configuration in other dbs. Note: This is called before the table is
     * created if it needs to be created.
     *
     * @param postConnection
     */
    @Field
    public void setPostConnection(String postConnection) {
        this.postConnectionString = Optional.of(postConnection);
    }

    /**
     * This applies to fields of type 'string' or 'varchar'.  If there's
     * a multivalued field in a metadata object, do you want the first value only
     * or should we concatenate these with the
     * {@link JDBCEmitter#setMultivaluedFieldDelimiter(String)}.
     * <p>
     * The default values as of 2.6.1 are {@link MultivaluedFieldStrategy#CONCATENATE}
     * and the default delimiter is &quot;, &quot;
     *
     * @param strategy
     * @throws TikaConfigException
     */
    @Field
    public void setMultivaluedFieldStrategy(String strategy) throws TikaConfigException {
        String lc = strategy.toLowerCase(Locale.US);
        if (lc.equals("first_only")) {
            setMultivaluedFieldStrategy(MultivaluedFieldStrategy.FIRST_ONLY);
        } else if (lc.equals("concatenate")) {
            setMultivaluedFieldStrategy(MultivaluedFieldStrategy.CONCATENATE);
        } else {
            throw new TikaConfigException("I'm sorry, I only recogize 'first_only' and " +
                    "'concatenate'. I don't mind '" + strategy + "'");
        }
    }

    public void setMultivaluedFieldStrategy(MultivaluedFieldStrategy multivaluedFieldStrategy) {
        this.multivaluedFieldStrategy = multivaluedFieldStrategy;
    }

    /**
     * See {@link JDBCEmitter#setMultivaluedFieldDelimiter(String)}
     *
     * @param delimiter
     */
    @Field
    public void setMultivaluedFieldDelimiter(String delimiter) {
        this.multivaluedFieldDelimiter = delimiter;
    }

    /**
     * The implementation of keys should be a LinkedHashMap because
     * order matters!
     * <p>
     * Key is the name of the metadata field, value is the type of column:
     * boolean, string, int, long
     *
     * @param keys
     */
    @Field
    public void setKeys(Map<String, String> keys) {
        this.keys = keys;
    }

    public void setAttachmentStrategy(AttachmentStrategy attachmentStrategy) {
        this.attachmentStrategy = attachmentStrategy;
    }

    @Field
    public void setAttachmentStrategy(String attachmentStrategy) {
        if ("all".equalsIgnoreCase(attachmentStrategy)) {
            setAttachmentStrategy(AttachmentStrategy.ALL);
        } else if ("first_only".equalsIgnoreCase(attachmentStrategy)) {
            setAttachmentStrategy(AttachmentStrategy.FIRST_ONLY);
        } else {
            throw new IllegalArgumentException("attachmentStrategy must be 'all' or 'first_only'");
        }
    }

    /**
     * This executes the emit with each call.  For more efficient
     * batch execution use {@link #emit(List)}.
     *
     * @param emitKey      emit key
     * @param metadataList list of metadata per file
     * @throws IOException
     * @throws TikaEmitterException
     */
    @Override
    public void emit(String emitKey, List<Metadata> metadataList)
            throws IOException, TikaEmitterException {
        if (metadataList == null || metadataList.size() < 1) {
            return;
        }
        List<EmitData> emitDataList = new ArrayList<>();
        emitDataList.add(new EmitData(new EmitKey("", emitKey), metadataList));
        emit(emitDataList);
    }

    @Override
    public void emit(List<? extends EmitData> emitData) throws IOException, TikaEmitterException {
        int tries = 0;
        Exception ex = null;
        while (tries++ <= maxRetries) {
            try {
                emitNow(emitData);
                return;
            } catch (SQLException e) {
                try {
                    reconnect();
                } catch (SQLException exc) {
                    throw new TikaEmitterException("couldn't reconnect!", exc);
                }
                ex = e;
            }
        }
        throw new TikaEmitterException("Couldn't emit " + emitData.size() + " records.", ex);
    }

    private void emitNow(List<? extends EmitData> emitData) throws SQLException {
        if (attachmentStrategy == AttachmentStrategy.FIRST_ONLY) {
            for (EmitData d : emitData) {
                insertFirstOnly(d.getEmitKey().getEmitKey(), d.getMetadataList());
                insertStatement.addBatch();
            }
        } else {
            for (EmitData d : emitData) {
                insertAll(d.getEmitKey().getEmitKey(), d.getMetadataList());
            }
        }
        insertStatement.executeBatch();
    }

    private void insertAll(String emitKey, List<Metadata> metadataList) throws SQLException {

        for (int i = 0; i < metadataList.size(); i++) {
            insertStatement.clearParameters();
            int col = 0;
            insertStatement.setString(++col, emitKey);
            insertStatement.setInt(++col, i);
            for (Map.Entry<String, String> e : keys.entrySet()) {
                updateValue(insertStatement, ++col, e.getKey(), e.getValue(), i, metadataList);
            }
            insertStatement.addBatch();
        }
    }

    private void insertFirstOnly(String emitKey, List<Metadata> metadataList) throws SQLException {
        insertStatement.clearParameters();
        int i = 0;
        DateFormat[] dateFormats = new DateFormat[TIKA_DATE_PATTERNS.length];
        for (int j = 0; j < TIKA_DATE_PATTERNS.length; j++) {
            dateFormats[i] = new SimpleDateFormat(TIKA_DATE_PATTERNS[j], Locale.US);
        }
        insertStatement.setString(++i, emitKey);
        for (Map.Entry<String, String> e : keys.entrySet()) {
            updateValue(insertStatement, ++i, e.getKey(), e.getValue(), 0, metadataList);
        }
    }

    private void reconnect() throws SQLException {
        SQLException ex = null;
        for (int i = 0; i < 3; i++) {
            try {
                tryClose();
                createConnection();
                insertStatement = connection.prepareStatement(insert);
                return;
            } catch (SQLException e) {
                LOGGER.warn("couldn't reconnect to db", e);
                ex = e;
            }
        }
        throw ex;
    }

    private void tryClose() {
        if (insertStatement != null) {
            try {
                insertStatement.close();
            } catch (SQLException e) {
                LOGGER.warn("exception closing insert", e);
            }
        }

        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException e) {
                LOGGER.warn("exception closing connection", e);
            }
        }
    }

    private void createConnection() throws SQLException {
        connection = DriverManager.getConnection(connectionString);
        if (postConnectionString.isPresent()) {
            try (Statement st = connection.createStatement()) {
                st.execute(postConnectionString.get());
            }
        }
    }

    private void updateValue(PreparedStatement insertStatement, int i, String key, String type,
                             int metadataListIndex, List<Metadata> metadataList)
            throws SQLException {
        Metadata metadata = metadataList.get(metadataListIndex);
        String val = getVal(metadata, key, type);
        String lcType = type.toLowerCase(Locale.US);
        if (lcType.startsWith("varchar")) {
            updateVarchar(key, lcType, insertStatement, i, val);
            return;
        }
        switch (lcType) {
            case "string":
                updateString(insertStatement, i, val);
                break;
            case "bool":
            case "boolean":
                updateBoolean(insertStatement, i, val);
                break;
            case "int":
            case "integer":
                updateInteger(insertStatement, i, val);
                break;
            case "bigint":
            case "long":
                updateLong(insertStatement, i, val);
                break;
            case "float":
                updateFloat(insertStatement, i, val);
                break;
            case "double":
                updateDouble(insertStatement, i, val);
                break;
            case "timestamp":
                updateTimestamp(insertStatement, i, val, dateFormats);
                break;
            default:
                throw new IllegalArgumentException("Can only process: 'string', 'boolean', 'int' " +
                        "and 'long' types so far.  Please open a ticket to request: " + type);
        }
    }

    private String getVal(Metadata metadata, String key, String type) {
        if (!type.equals("string") && !type.startsWith("varchar")) {
            return metadata.get(key);
        }
        if (multivaluedFieldStrategy == MultivaluedFieldStrategy.FIRST_ONLY) {
            return metadata.get(key);
        }
        String[] vals = metadata.getValues(key);
        if (vals.length == 0) {
            return null;
        } else if (vals.length == 1) {
            return vals[0];
        }

        int i = 0;
        StringBuilder sb = new StringBuilder();
        for (String val : metadata.getValues(key)) {
            if (StringUtils.isBlank(val)) {
                continue;
            }
            if (i > 0) {
                sb.append(multivaluedFieldDelimiter);
            }
            sb.append(val);
            i++;
        }
        return sb.toString();
    }

    private void updateDouble(PreparedStatement insertStatement, int i, String val)
            throws SQLException {
        if (StringUtils.isBlank(val)) {
            insertStatement.setNull(i, Types.DOUBLE);
            return;
        }
        Double d = Double.parseDouble(val);
        insertStatement.setDouble(i, d);
    }

    private void updateVarchar(String key, String type, PreparedStatement insertStatement, int i,
                               String val) throws SQLException {
        if (StringUtils.isBlank(val)) {
            updateString(insertStatement, i, val);
            return;
        }
        Matcher m = Pattern.compile("varchar\\((\\d+)\\)").matcher(type);
        if (m.find()) {
            int len = Integer.parseInt(m.group(1));
            if (val.length() > len) {
                int origLength = val.length();
                val = val.substring(0, len);
                LOGGER.warn("truncating varchar ({}) from {} to {}", key, origLength, len);
            }
            updateString(insertStatement, i, val);
            return;
        }
        LOGGER.warn("couldn't parse varchar?! {}", type);
        updateString(insertStatement, i, null);
    }

    private void updateTimestamp(PreparedStatement insertStatement, int i, String val,
                                 DateFormat[] dateFormats) throws SQLException {
        if (StringUtils.isBlank(val)) {
            insertStatement.setNull(i, Types.TIMESTAMP);
            return;
        }

        for (DateFormat df : dateFormats) {
            try {
                Date d = df.parse(val);
                insertStatement.setTimestamp(i, new Timestamp(d.getTime()));
                return;
            } catch (ParseException e) {
                //ignore
            }
        }
        LOGGER.warn("Couldn't parse {}" + val);
        insertStatement.setNull(i, Types.TIMESTAMP);
    }

    private void updateFloat(PreparedStatement insertStatement, int i, String val)
            throws SQLException {
        if (StringUtils.isBlank(val)) {
            insertStatement.setNull(i, Types.FLOAT);
        } else {
            insertStatement.setFloat(i, Float.parseFloat(val));
        }
    }

    private void updateLong(PreparedStatement insertStatement, int i, String val)
            throws SQLException {
        if (StringUtils.isBlank(val)) {
            insertStatement.setNull(i, Types.BIGINT);
        } else {
            insertStatement.setLong(i, Long.parseLong(val));
        }
    }

    private void updateInteger(PreparedStatement insertStatement, int i, String val)
            throws SQLException {
        if (StringUtils.isBlank(val)) {
            insertStatement.setNull(i, Types.INTEGER);
        } else {
            insertStatement.setInt(i, Integer.parseInt(val));
        }
    }

    private void updateBoolean(PreparedStatement insertStatement, int i, String val)
            throws SQLException {
        if (StringUtils.isBlank(val)) {
            insertStatement.setNull(i, Types.BOOLEAN);
        } else {
            insertStatement.setBoolean(i, Boolean.parseBoolean(val));
        }
    }

    private void updateString(PreparedStatement insertStatement, int i, String val)
            throws SQLException {
        if (val == null) {
            insertStatement.setNull(i, Types.VARCHAR);
        } else {
            insertStatement.setString(i, val);
        }
    }

    @Override
    public void initialize(Map<String, Param> params) throws TikaConfigException {

        try {
            createConnection();
        } catch (SQLException e) {
            throw new TikaConfigException("couldn't open connection: " + connectionString, e);
        }
        if (!StringUtils.isBlank(createTable)) {
            //synchronize table creation
            READ_WRITE_LOCK.writeLock().lock();
            try {
                String tableCreationString = connectionString + " " + createTable;
                if (!TABLES_CREATED.contains(tableCreationString)) {
                    try (Statement st = connection.createStatement()) {
                        st.execute(createTable);
                        if (!StringUtils.isBlank(alterTable)) {
                            st.execute(alterTable);
                        }
                        TABLES_CREATED.add(tableCreationString);
                    } catch (SQLException e) {
                        throw new TikaConfigException("can't create table", e);
                    }
                }
            } finally {
                READ_WRITE_LOCK.writeLock().unlock();
            }
        }
        try {
            insertStatement = connection.prepareStatement(insert);
        } catch (SQLException e) {
            throw new TikaConfigException("can't create insert statement", e);
        }
    }

    @Override
    public void checkInitialization(InitializableProblemHandler problemHandler)
            throws TikaConfigException {
        //require
    }

    /**
     * @throws IOException
     */
    @Override
    public void close() throws IOException {
        try {
            insertStatement.close();
        } catch (SQLException e) {
            LOGGER.warn("problem closing insert", e);
        }
        try {
            connection.close();
        } catch (SQLException e) {
            throw new IOException(e);
        }
    }

}
