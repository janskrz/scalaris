/**********************************************************************
Copyright (c) 2008 Erik Bengtson and others. All rights reserved.
Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.

Contributors:
2013 Orange - port to Scalaris key/value store
    ...
 **********************************************************************/
package org.datanucleus.store.scalaris;

import java.util.Iterator;

import org.datanucleus.ExecutionContext;
import org.datanucleus.exceptions.NucleusDataStoreException;
import org.datanucleus.exceptions.NucleusException;
import org.datanucleus.exceptions.NucleusObjectNotFoundException;
import org.datanucleus.metadata.AbstractClassMetaData;
import org.datanucleus.state.ObjectProvider;
import org.datanucleus.store.AbstractPersistenceHandler;
import org.datanucleus.store.StoreManager;
import org.datanucleus.store.connection.ManagedConnection;
import org.datanucleus.store.scalaris.fieldmanager.FetchFieldManager;
import org.datanucleus.store.scalaris.fieldmanager.StoreFieldManager;
import org.datanucleus.util.Localiser;
import org.datanucleus.util.NucleusLogger;

import com.orange.org.json.JSONException;
import com.orange.org.json.JSONObject;

import de.zib.scalaris.AbortException;
import de.zib.scalaris.ConnectionException;
import de.zib.scalaris.NotAListException;
import de.zib.scalaris.NotFoundException;
import de.zib.scalaris.Transaction;
import de.zib.scalaris.UnknownException;

@SuppressWarnings({ "rawtypes", "unchecked" })
public class ScalarisPersistenceHandler extends AbstractPersistenceHandler {

    /** Setup localizer for messages. */
    static {
        Localiser.registerBundle("org.datanucleus.store.scalaris.Localisation",
                ScalarisStoreManager.class.getClassLoader());
    }

    ScalarisPersistenceHandler(StoreManager storeMgr) {
        super(storeMgr);
    }

    public void close() {
        // nothing to do
    }

    /**
     * Populates JSONObject with information of the object provider
     * 
     * @param jsonobj
     *            updated with data from op
     * @param op
     *            data source
     * @return primary key as string
     */
    private void populateJsonObj(JSONObject jsonobj, ObjectProvider op) {
        AbstractClassMetaData acmd = op.getClassMetaData();
        int[] fieldNumbers = acmd.getAllMemberPositions();
        op.provideFields(fieldNumbers, new StoreFieldManager(op, jsonobj, true));
    }


    public void insertObject(ObjectProvider op) {
        System.out.println("INSERT");
        // Check if read-only so update not permitted
        assertReadOnlyForUpdateOfObject(op);

        ExecutionContext ec = op.getExecutionContext();
        ManagedConnection mconn = storeMgr.getConnection(ec);
        de.zib.scalaris.Connection conn = (de.zib.scalaris.Connection) mconn
                .getConnection();

        try {
            long startTime = System.currentTimeMillis();
            if (NucleusLogger.DATASTORE_PERSIST.isDebugEnabled()) {
                NucleusLogger.DATASTORE_PERSIST.debug(Localiser.msg(
                        "Scalaris.Insert.Start", op.getObjectAsPrintable(),
                        op.getInternalObjectId()));
            }

            JSONObject jsonobj = new JSONObject();
            final String id = ScalarisUtils.generatePersistableIdentity(op);
            populateJsonObj(jsonobj, op);

            if (NucleusLogger.DATASTORE_NATIVE.isDebugEnabled()) {
                NucleusLogger.DATASTORE_NATIVE.debug("POST "
                        + jsonobj.toString());
            }

            System.out.println("id=" + id + " json=" + jsonobj.toString());

            Transaction t1 = new Transaction(conn);
            try {
                ScalarisUtils.performScalarisManagementForInsert(op, jsonobj, t1);
                t1.write(id, jsonobj.toString());
                t1.commit();
            } catch (ConnectionException e) {
                throw new NucleusException(e.getMessage(), e);
            } catch (UnknownException e) {
                throw new NucleusException(e.getMessage(), e);
            } catch (ClassCastException e) {
                throw new NucleusException(e.getMessage(), e);
            } catch (NotAListException e) {
                throw new NucleusException(e.getMessage(), e);
            }

            if (ec.getStatistics() != null) {
                // Add to statistics
                ec.getStatistics().incrementNumWrites();
                ec.getStatistics().incrementInsertCount();
            }

            if (NucleusLogger.DATASTORE_PERSIST.isDebugEnabled()) {
                NucleusLogger.DATASTORE_PERSIST.debug(Localiser.msg(
                        "Scalaris.ExecutionTime",
                        (System.currentTimeMillis() - startTime)));
            }
        } catch (AbortException e) {
            throw new NucleusException(e.getMessage(), e);
        } catch (UnknownException e) {
            throw new NucleusException(e.getMessage(), e);
        } finally {
            mconn.release();
        }
    }

    public void updateObject(ObjectProvider op, int[] updatedFieldNumbers) {
        System.out.println("UPDATE " + ScalarisUtils.getPersistableIdentity(op));
        // Check if read-only so update not permitted
        assertReadOnlyForUpdateOfObject(op);

        ExecutionContext ec = op.getExecutionContext();
        ManagedConnection mconn = storeMgr.getConnection(ec);
        de.zib.scalaris.Connection conn = (de.zib.scalaris.Connection) mconn
                .getConnection();

        try {
            AbstractClassMetaData cmd = op.getClassMetaData();

            long startTime = System.currentTimeMillis();
            if (NucleusLogger.DATASTORE_PERSIST.isDebugEnabled()) {
                StringBuffer fieldStr = new StringBuffer();
                for (int i = 0; i < updatedFieldNumbers.length; i++) {
                    if (i > 0) {
                        fieldStr.append(",");
                    }
                    fieldStr.append(cmd
                            .getMetaDataForManagedMemberAtAbsolutePosition(
                                    updatedFieldNumbers[i]).getName());
                }
                NucleusLogger.DATASTORE_PERSIST.debug(Localiser.msg(
                        "Scalaris.Update.Start", op.getObjectAsPrintable(),
                        op.getInternalObjectId(), fieldStr.toString()));
            }

            JSONObject changedVals = new JSONObject();

            final String id = ScalarisUtils.getPersistableIdentity(op);
            op.provideFields(updatedFieldNumbers, new StoreFieldManager(op,
                    changedVals, false));

            System.out.println("update id=" + id);

            if (NucleusLogger.DATASTORE_NATIVE.isDebugEnabled()) {
                NucleusLogger.DATASTORE_NATIVE.debug("PUT "
                        + changedVals.toString());
            }


            Transaction t1 = new Transaction(conn);
            try {
                JSONObject stored = new JSONObject(t1.read(id).stringValue());
                JSONObject changedValsOld = new JSONObject();
                // update stored object values
                Iterator<String> keyIter = changedVals.keys();
                while (keyIter.hasNext()) {
                    String key = keyIter.next();
                    if (stored.has(key)) {
                        changedValsOld.put(key, stored.get(key));
                    }
                    stored.put(key, changedVals.get(key));
                }

                ScalarisUtils.performScalarisManagementForUpdate(op, changedVals, changedValsOld, t1);
                t1.write(id, stored.toString());
                System.out.println("Updated JSON: " + stored.toString());
                t1.commit();
            } catch (ConnectionException e) {
                throw new NucleusException(e.getMessage(), e);
            } catch (AbortException e) {
                throw new NucleusException(e.getMessage(), e);
            }catch (UnknownException e) {
                throw new NucleusException(e.getMessage(), e);
            }catch (NotFoundException e) {
                // if we have an update we should already have this object stored
                throw new NucleusException("Could not update object since its original value was not found", e);
            } catch (ClassCastException e) {
                throw new NucleusException("The stored object has a broken structure", e);
            } catch (NotAListException e) {
                throw new NucleusException("The stored object has a broken structure", e);
            } catch (JSONException e) {
                throw new NucleusException("The stored object has a broken structure", e);
            }

            if (ec.getStatistics() != null) {
                // Add to statistics
                ec.getStatistics().incrementNumWrites();
                ec.getStatistics().incrementUpdateCount();
            }

            if (NucleusLogger.DATASTORE_PERSIST.isDebugEnabled()) {
                NucleusLogger.DATASTORE_PERSIST.debug(Localiser.msg(
                        "Scalaris.ExecutionTime",
                        (System.currentTimeMillis() - startTime)));
            }
        } finally {
            mconn.release();
        }
    }

    /**
     * Deletes a persistent object from the database. The delete can take place
     * in several steps, one delete per table that it is stored in. e.g When
     * deleting an object that uses "new-table" inheritance for each level of
     * the inheritance tree then will get an DELETE for each table. When
     * deleting an object that uses "complete-table" inheritance then will get a
     * single DELETE for its table.
     * 
     * @param op
     *            The ObjectProvider of the object to be deleted.
     * 
     * @throws NucleusDataStoreException
     *             when an error occurs in the datastore communication
     */
    public void deleteObject(ObjectProvider op) {
        System.out.println("DELETE");
        // Check if read-only so update not permitted
        assertReadOnlyForUpdateOfObject(op);

        ExecutionContext ec = op.getExecutionContext();
        ManagedConnection mconn = storeMgr.getConnection(ec);
        de.zib.scalaris.Connection conn = (de.zib.scalaris.Connection) mconn
                .getConnection();

        try {
            long startTime = System.currentTimeMillis();
            if (NucleusLogger.DATASTORE_PERSIST.isDebugEnabled()) {
                NucleusLogger.DATASTORE_PERSIST.debug(Localiser.msg(
                        "Scalaris.Delete.Start", op.getObjectAsPrintable(),
                        op.getInternalObjectId()));
            }

            final String id = ScalarisUtils.getPersistableIdentity(op);
            System.out.println("deleting object with key=" + id);

            Transaction t1 = new Transaction(conn);

            try {
                JSONObject obj  = new JSONObject(t1.read(id).stringValue());
                ScalarisUtils.performScalarisManagementForDelete(op, obj, t1);
                t1.write(id, ScalarisUtils.DELETED_RECORD_VALUE);
                t1.commit();
                System.out.println("deleted id=" + id);
            } catch (ConnectionException e) {
                throw new NucleusDataStoreException(e.getMessage(), e);
            } catch (UnknownException e) {
                throw new NucleusDataStoreException(e.getMessage(), e);
            } catch (AbortException e) {
                throw new NucleusDataStoreException(e.getMessage(), e);
            } catch (ClassCastException e) {
                throw new NucleusDataStoreException(e.getMessage(), e);
            } catch (NotAListException e) {
                throw new NucleusDataStoreException(e.getMessage(), e);
            } catch (NotFoundException e) {
                throw new NucleusDataStoreException(e.getMessage(), e);
            } catch (JSONException e) {
                throw new NucleusDataStoreException(e.getMessage(), e);
            }

            if (ec.getStatistics() != null) {
                ec.getStatistics().incrementNumWrites();
                ec.getStatistics().incrementDeleteCount();
            }

            if (NucleusLogger.DATASTORE_PERSIST.isDebugEnabled()) {
                NucleusLogger.DATASTORE_PERSIST.debug(Localiser.msg(
                        "Scalaris.ExecutionTime",
                        (System.currentTimeMillis() - startTime)));
            }
        } finally {
            mconn.release();
        }
    }

    /**
     * Fetches (fields of) a persistent object from the database. This does a
     * single SELECT on the candidate of the class in question. Will join to
     * inherited tables as appropriate to get values persisted into other
     * tables. Can also join to the tables of related objects (1-1, N-1) as
     * neccessary to retrieve those objects.
     * 
     * @param op
     *            Object Provider of the object to be fetched.
     * @param memberNumbers
     *            The numbers of the members to be fetched.
     * @throws NucleusObjectNotFoundException
     *             if the object doesn't exist
     * @throws NucleusDataStoreException
     *             when an error occurs in the datastore communication
     */
    public void fetchObject(ObjectProvider op, int[] fieldNumbers) {
        System.out.println("FETCH " + op.getObject().getClass().getName());

        ExecutionContext ec = op.getExecutionContext();
        ManagedConnection mconn = storeMgr.getConnection(ec);
        de.zib.scalaris.Connection conn = (de.zib.scalaris.Connection) mconn
                .getConnection();

        try {
            final long startTime = System.currentTimeMillis();

            final String key = ScalarisUtils.getPersistableIdentity(op);
            System.out.println("FETCH KEY: " + key);

            try {
                Transaction t1 = new Transaction(conn);

                JSONObject result = new JSONObject(t1.read(key).stringValue());
                if (ScalarisUtils.isDeletedRecord(result)) {
                    throw new NucleusObjectNotFoundException(
                            "Record has been deleted");
                }
                final String declaredClassQName = result.getString("class");
                final Class declaredClass = op.getExecutionContext()
                        .getClassLoaderResolver()
                        .classForName(declaredClassQName);
                final Class objectClass = op.getObject().getClass();

                if (!objectClass.isAssignableFrom(declaredClass)) {
                        System.out.println("Type found in db not compatible with requested type");
                    throw new NucleusObjectNotFoundException(
                            "Type found in db not compatible with requested type");
                }

                op.replaceFields(fieldNumbers, new FetchFieldManager(op, result));

                t1.commit();

                if (NucleusLogger.DATASTORE_NATIVE.isDebugEnabled()) {
                    NucleusLogger.DATASTORE_NATIVE
                            .debug("GET " + result.toString());
                }
            } catch (NotFoundException e) {
                throw new NucleusObjectNotFoundException(e.getMessage(), e);
            } catch (ConnectionException e) {
                throw new NucleusDataStoreException(e.getMessage(), e);
            } catch (UnknownException e) {
                throw new NucleusDataStoreException(e.getMessage(), e);
            } catch (AbortException e) {
                throw new NucleusDataStoreException(e.getMessage(), e);
            } catch (JSONException e) {
                throw new NucleusDataStoreException(e.getMessage(), e);
            }

            if (ec.getStatistics() != null) {
                // Add to statistics
                ec.getStatistics().incrementNumReads();
                ec.getStatistics().incrementFetchCount();
            }

            if (NucleusLogger.DATASTORE_RETRIEVE.isDebugEnabled()) {
                NucleusLogger.DATASTORE_RETRIEVE.debug(Localiser.msg(
                        "Scalaris.ExecutionTime",
                        (System.currentTimeMillis() - startTime)));
            }
        } finally {
            mconn.release();
        }
    }

    /**
     * Method to return a persistable object with the specified id. Optional
     * operation for StoreManagers. Should return a (at least) hollow
     * PersistenceCapable object if the store manager supports the operation. If
     * the StoreManager is managing the in-memory object instantiation (as part
     * of co-managing the object lifecycle in general), then the StoreManager
     * has to create the object during this call (if it is not already created).
     * Most relational databases leave the in-memory object instantion to Core,
     * but some object databases may manage the in-memory object instantion,
     * effectively preventing Core of doing this.
     * <p>
     * StoreManager implementations may simply return null, indicating that they
     * leave the object instantiate to us. Other implementations may instantiate
     * the object in question (whether the implementation may trust that the
     * object is not already instantiated has still to be determined). If an
     * implementation believes that an object with the given ID should exist,
     * but in fact does not exist, then the implementation should throw a
     * RuntimeException. It should not silently return null in this case.
     * </p>
     * 
     * @param ec
     *            execution context
     * @param id
     *            the id of the object in question.
     * @return a persistable object with a valid object state (for example:
     *         hollow) or null, indicating that the implementation leaves the
     *         instantiation work to us.
     */
    public Object findObject(ExecutionContext ec, Object id) {
        System.out.println("FIND id=" + id.getClass());

        return null;
    }

    /**
     * Locates this object in the datastore.
     * 
     * @param op
     *            ObjectProvider for the object to be found
     * @throws NucleusObjectNotFoundException
     *             if the object doesnt exist
     * @throws NucleusDataStoreException
     *             when an error occurs in the datastore communication
     */
    public void locateObject(ObjectProvider op) {
        System.out.println("LOCATE");

        final String key = ScalarisUtils.getPersistableIdentity(op);

        final ExecutionContext ec = op.getExecutionContext();

        ManagedConnection mconn = storeMgr.getConnection(ec);
        final de.zib.scalaris.Connection conn = (de.zib.scalaris.Connection) mconn.getConnection();

        try {
            Transaction t1 = new Transaction(conn);
            final String indb = t1.read(key).stringValue();

            System.out.println("locate : " + indb);
            t1.commit();
        } catch (NotFoundException e) {
            throw new NucleusObjectNotFoundException(e.getMessage(), e);
        } catch (ConnectionException e) {
            throw new NucleusDataStoreException(e.getMessage(), e);
        } catch (UnknownException e) {
            throw new NucleusDataStoreException(e.getMessage(), e);
        } catch (AbortException e) {
            throw new NucleusDataStoreException(e.getMessage(), e);
        }

        if (ec.getStatistics() != null) {
            // Add to statistics
            ec.getStatistics().incrementNumReads();
        }
    }
}
