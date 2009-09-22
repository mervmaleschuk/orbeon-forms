/**
 * Copyright (C) 2009 Orbeon, Inc.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the
 * GNU Lesser General Public License as published by the Free Software Foundation; either version
 * 2.1 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Lesser General Public License for more details.
 *
 * The full text of the license is available at http://www.gnu.org/copyleft/lesser.html
 */
package org.orbeon.oxf.xforms.action.actions;

import org.orbeon.oxf.xforms.action.XFormsAction;
import org.orbeon.oxf.xforms.action.XFormsActionInterpreter;
import org.orbeon.oxf.xforms.event.XFormsEventObserver;
import org.orbeon.oxf.util.PropertyContext;
import org.orbeon.saxon.om.Item;
import org.dom4j.Element;

public class XXFormsJoinSubmissions extends XFormsAction {
    public void execute(XFormsActionInterpreter actionInterpreter, PropertyContext propertyContext, String targetEffectiveId,
                        XFormsEventObserver eventObserver, Element actionElement, boolean hasOverriddenContext, Item overriddenContext) {

        // Just process all background async submissions. The action will block until the method returns.
        actionInterpreter.getContainingDocument().processBackgroundAsynchronousSubmissions(propertyContext);
    }
}