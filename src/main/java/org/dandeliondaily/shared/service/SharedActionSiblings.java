package org.dandeliondaily.shared.service;

import java.util.ArrayList;
import java.util.List;

import org.hibernate.Query;
import org.hibernate.Session;
import org.openimmunizationsoftware.pt.model.ActionNext;
import org.openimmunizationsoftware.pt.model.ActionSetType;

/**
 * A linked-project action is one action recorded on each project it is shared
 * with, joined by a SHARED action set. Edits to one must reach all of them.
 */
public final class SharedActionSiblings {

    private SharedActionSiblings() {
    }

    /**
     * Returns the action plus its SHARED action-set siblings, ordered by id, or
     * just the action when it is not shared. Empty when the action is null.
     */
    public static List<ActionNext> resolve(Session dataSession, ActionNext selectedAction) {
        List<ActionNext> singleAction = new ArrayList<ActionNext>();
        if (selectedAction == null) {
            return singleAction;
        }
        singleAction.add(selectedAction);
        if (selectedAction.getActionSet() == null
                || selectedAction.getActionSet().getActionSetType() != ActionSetType.SHARED) {
            return singleAction;
        }
        Query siblingQuery = dataSession.createQuery(
                "from ActionNext an where an.actionSet.actionSetId = :actionSetId order by an.actionNextId");
        siblingQuery.setParameter("actionSetId", selectedAction.getActionSet().getActionSetId());
        @SuppressWarnings("unchecked")
        List<ActionNext> siblings = siblingQuery.list();
        return siblings == null || siblings.isEmpty() ? singleAction : siblings;
    }
}
