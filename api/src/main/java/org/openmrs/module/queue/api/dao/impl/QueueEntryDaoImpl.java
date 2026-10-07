/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.queue.api.dao.impl;

import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.CriteriaQuery;
import javax.persistence.criteria.Predicate;
import javax.persistence.criteria.Root;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.function.BiFunction;

import org.hibernate.Criteria;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.criterion.Criterion;
import org.hibernate.criterion.DetachedCriteria;
import org.hibernate.criterion.Order;
import org.hibernate.criterion.Projections;
import org.hibernate.criterion.Restrictions;
import org.hibernate.criterion.Subqueries;
import org.openmrs.Patient;
import org.openmrs.module.queue.api.dao.QueueEntryDao;
import org.openmrs.module.queue.api.search.QueueEntrySearchCriteria;
import org.openmrs.module.queue.model.Queue;
import org.openmrs.module.queue.model.QueueEntry;
import org.springframework.beans.factory.annotation.Qualifier;

@SuppressWarnings("unchecked")
public class QueueEntryDaoImpl extends AbstractBaseQueueDaoImpl<QueueEntry> implements QueueEntryDao {
	
	public QueueEntryDaoImpl(@Qualifier("sessionFactory") SessionFactory sessionFactory) {
		super(sessionFactory);
	}
	
	@Override
	public List<QueueEntry> getQueueEntries(QueueEntrySearchCriteria searchCriteria) {
		Criteria c = createCriteriaFromSearchCriteria(searchCriteria);
		c.addOrder(Order.desc("qe.sortWeight"));
		c.addOrder(Order.asc("qe.startedAt"));
		c.addOrder(Order.asc("qe.dateCreated"));
		c.addOrder(Order.asc("qe.queueEntryId"));
		return c.list();
	}
	
	@Override
	public Long getCountOfQueueEntries(QueueEntrySearchCriteria searchCriteria) {
		Criteria criteria = createCriteriaFromSearchCriteria(searchCriteria);
		criteria.setProjection(Projections.rowCount());
		return (Long) criteria.uniqueResult();
	}
	
	@Override
	public List<QueueEntry> getOverlappingQueueEntries(QueueEntrySearchCriteria searchCriteria) {
		Session session = getSessionFactory().getCurrentSession();
		CriteriaBuilder cb = session.getCriteriaBuilder();
		CriteriaQuery<QueueEntry> query = cb.createQuery(QueueEntry.class);
		Root<QueueEntry> root = query.from(QueueEntry.class);
		List<Predicate> predicates = new ArrayList<>();
		
		predicates.add(cb.equal(root.get("voided"), false));
		
		Collection<Queue> queues = searchCriteria.getQueues();
		if (queues != null) {
			if (queues.isEmpty()) {
				predicates.add(root.get("queue").isNull());
			} else {
				predicates.add(root.get("queue").in(searchCriteria.getQueues()));
			}
		}
		
		Patient patient = searchCriteria.getPatient();
		if (patient != null) {
			predicates.add(cb.equal(root.get("patient"), patient));
		}
		
		Date startedAt = searchCriteria.getStartedOn();
		if (startedAt != null) {
			// any queue entries that have either not ended or end after this queue entry starts
			predicates.add(cb.or(root.get("endedAt").isNull(), cb.greaterThan(root.get("endedAt"), startedAt)));
		}
		
		Date endedAt = searchCriteria.getEndedOn();
		if (endedAt != null) {
			// any queue entries that started before this queue entry ends
			predicates.add(cb.lessThan(root.get("startedAt"), endedAt));
		}
		
		query.where(cb.and(predicates.toArray(new Predicate[0])));
		
		return session.createQuery(query).list();
	}
	
	@Override
	public void flushSession() {
		getSessionFactory().getCurrentSession().flush();
	}
	
	@Override
	public boolean updateIfUnmodified(QueueEntry queueEntry, Date expectedDateChanged) {
		Session session = getSessionFactory().getCurrentSession();
		
		// This path issues a direct JPQL UPDATE and bypasses QueueEntryValidator; enforce the
		// strict-positive-duration invariant here so the DB never ends up with ended_at <= started_at.
		Date endedAt = queueEntry.getEndedAt();
		Date startedAt = queueEntry.getStartedAt();
		if (endedAt != null && startedAt != null && !endedAt.after(startedAt)) {
			throw new IllegalArgumentException(
			        "Queue entry endedAt (" + endedAt + ") must be after startedAt (" + startedAt + ")");
		}
		
		// Evict the entity to prevent Hibernate from auto-flushing changes
		session.evict(queueEntry);
		
		// Build conditional update query - only succeeds if dateChanged matches expected value
		StringBuilder jpql = new StringBuilder();
		jpql.append("UPDATE QueueEntry qe SET ");
		jpql.append("qe.endedAt = :endedAt ");
		jpql.append("WHERE qe.queueEntryId = :id ");
		
		if (expectedDateChanged == null) {
			jpql.append("AND qe.dateChanged IS NULL");
		} else {
			jpql.append("AND qe.dateChanged = :expectedDateChanged");
		}
		
		javax.persistence.Query query = session.createQuery(jpql.toString());
		query.setParameter("endedAt", endedAt);
		query.setParameter("id", queueEntry.getQueueEntryId());
		if (expectedDateChanged != null) {
			query.setParameter("expectedDateChanged", expectedDateChanged);
		}
		
		int rowsUpdated = query.executeUpdate();
		return rowsUpdated > 0;
	}
	
	/**
	 * Convert the given {@link QueueEntrySearchCriteria} into ORM criteria
	 */
	private Criteria createCriteriaFromSearchCriteria(QueueEntrySearchCriteria searchCriteria) {
		Criteria c = getCurrentSession().createCriteria(QueueEntry.class, "qe");
		c.createAlias("queue", "q");
		toRestrictions(searchCriteria, "qe", "q").forEach(c::add);
		if (searchCriteria.isLatestPerPatient()) {
			// Keep an entry only if no other entry for the same patient that matches the same criteria
			// started after it (ties broken by id, so exactly one entry survives per patient). The
			// subquery repeats every restriction so "latest" is judged within the requested population.
			DetachedCriteria later = DetachedCriteria.forClass(QueueEntry.class, "later");
			later.createAlias("later.queue", "laterQueue");
			toRestrictions(searchCriteria, "later", "laterQueue").forEach(later::add);
			later.add(Restrictions.eqProperty("later.patient", "qe.patient"));
			later.add(Restrictions.or(Restrictions.gtProperty("later.startedAt", "qe.startedAt"),
			    Restrictions.and(Restrictions.eqProperty("later.startedAt", "qe.startedAt"),
			        Restrictions.gtProperty("later.queueEntryId", "qe.queueEntryId"))));
			later.setProjection(Projections.id());
			c.add(Subqueries.notExists(later));
		}
		return c;
	}
	
	/**
	 * The restrictions the given {@link QueueEntrySearchCriteria} describes, expressed against the
	 * given aliases for the queue entry and its queue, so they can be applied to both the main query
	 * and the latest-per-patient subquery
	 */
	private List<Criterion> toRestrictions(QueueEntrySearchCriteria searchCriteria, String qe, String q) {
		List<Criterion> restrictions = new ArrayList<>();
		if (!searchCriteria.isIncludedVoided()) {
			restrictions.add(Restrictions.eq(qe + ".voided", false));
		}
		addCollectionRestriction(restrictions, qe + ".queue", searchCriteria.getQueues());
		addCollectionRestriction(restrictions, q + ".location", searchCriteria.getLocations());
		addCollectionRestriction(restrictions, q + ".service", searchCriteria.getServices());
		addRestriction(restrictions, qe + ".patient", searchCriteria.getPatient(), Restrictions::eq);
		addRestriction(restrictions, qe + ".visit", searchCriteria.getVisit(), Restrictions::eq);
		addCollectionRestriction(restrictions, qe + ".priority", searchCriteria.getPriorities());
		addCollectionRestriction(restrictions, qe + ".status", searchCriteria.getStatuses());
		addCollectionRestriction(restrictions, qe + ".locationWaitingFor", searchCriteria.getLocationsWaitingFor());
		addCollectionRestriction(restrictions, qe + ".providerWaitingFor", searchCriteria.getProvidersWaitingFor());
		addCollectionRestriction(restrictions, qe + ".queueComingFrom", searchCriteria.getQueuesComingFrom());
		addRestriction(restrictions, qe + ".startedAt", searchCriteria.getStartedOnOrAfter(), Restrictions::ge);
		addRestriction(restrictions, qe + ".startedAt", searchCriteria.getStartedOnOrBefore(), Restrictions::le);
		addRestriction(restrictions, qe + ".startedAt", searchCriteria.getStartedOn(), Restrictions::eq);
		addRestriction(restrictions, qe + ".endedAt", searchCriteria.getEndedOnOrAfter(), Restrictions::ge);
		addRestriction(restrictions, qe + ".endedAt", searchCriteria.getEndedOnOrBefore(), Restrictions::le);
		addRestriction(restrictions, qe + ".endedAt", searchCriteria.getEndedOn(), Restrictions::eq);
		if (searchCriteria.getHasVisit() == Boolean.TRUE) {
			restrictions.add(Restrictions.isNotNull(qe + ".visit"));
		} else if (searchCriteria.getHasVisit() == Boolean.FALSE) {
			restrictions.add(Restrictions.isNull(qe + ".visit"));
		}
		if (searchCriteria.getIsEnded() == Boolean.TRUE) {
			restrictions.add(Restrictions.isNotNull(qe + ".endedAt"));
		} else if (searchCriteria.getIsEnded() == Boolean.FALSE) {
			restrictions.add(Restrictions.isNull(qe + ".endedAt"));
		}
		return restrictions;
	}
	
	private void addRestriction(List<Criterion> restrictions, String property, Object value,
	        BiFunction<String, Object, Criterion> restriction) {
		if (value != null) {
			restrictions.add(restriction.apply(property, value));
		}
	}
	
	/**
	 * A null collection does not limit; an empty one limits to entries where the property is null
	 */
	private void addCollectionRestriction(List<Criterion> restrictions, String property, Collection<?> values) {
		if (values != null) {
			restrictions.add(values.isEmpty() ? Restrictions.isNull(property) : Restrictions.in(property, values));
		}
	}
}
