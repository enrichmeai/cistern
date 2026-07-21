package com.enrichmeai.cistern.wac;

import com.enrichmeai.cistern.core.vocab.Acl;

import org.apache.jena.rdf.model.Property;

/**
 * How an effective ACL came to govern the resource being evaluated, which decides <em>which</em>
 * authorizations in it apply. A closed set of two, so an enum (ground rule 7).
 *
 * <p>WAC gives an authorization two ways to name a target, and they are not interchangeable:
 * {@code acl:accessTo} "denotes the specific resource", while {@code acl:default} "denotes a
 * container resource whose Authorization applies to lower hierarchy members". So the same ACL
 * document yields different permissions depending on whether it was found <em>on</em> the
 * resource or <em>above</em> it.
 *
 * <p>Getting this wrong is a silent over-grant: treat an inherited ACL's {@code acl:accessTo}
 * statements as applying to a child and every rule written for the container alone leaks down
 * the tree. The distinction is carried in the type so the engine cannot forget it — the
 * caller (T5.1 discovery) knows where it found the ACL and says so.
 */
public enum AclScope {

    /**
     * The ACL was found on the resource itself, so its {@code acl:accessTo} authorizations
     * apply and its {@code acl:default} ones do not.
     */
    ACCESS_TO(Acl.ACCESS_TO),

    /**
     * The ACL was inherited from an ancestor container, so only its {@code acl:default}
     * authorizations apply.
     */
    INHERITED(Acl.DEFAULT);

    private final Property targetPredicate;

    AclScope(Property targetPredicate) {
        this.targetPredicate = targetPredicate;
    }

    /** The predicate an authorization must use to name a target under this scope. */
    public Property targetPredicate() {
        return targetPredicate;
    }
}
