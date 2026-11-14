// Written for Thoughts. License is GPLv3.

package com.github.barteksc.pdfviewer;

import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.customview.widget.ExploreByTouchHelper;

import java.util.Collections;
import java.util.List;

/**
 * The agent proposal markers on screen as accessibility nodes: each says what a tap does (accept)
 * and that a long press shows more, and both actions work from an accessibility service.
 */
final class AgentProposalMarkerAccessibility extends ExploreByTouchHelper {

    /** A marker on screen, in view coordinates. */
    static final class VisibleMarker {
        final String proposalId;
        final RectF viewBounds;

        VisibleMarker(String proposalId, RectF viewBounds) {
            this.proposalId = proposalId;
            this.viewBounds = viewBounds;
        }
    }

    private final PDFView pdfView;
    private String description = "";
    private PDFView.AgentProposalMarkerActions actions;
    /** The markers as the service last saw them; node ids are indices into it. */
    private List<VisibleMarker> markers = Collections.emptyList();

    AgentProposalMarkerAccessibility(PDFView pdfView) {
        super(pdfView);
        this.pdfView = pdfView;
    }

    void configure(String description, PDFView.AgentProposalMarkerActions actions) {
        this.description = description == null ? "" : description;
        this.actions = actions;
        invalidateRoot();
    }

    @Override
    protected int getVirtualViewAt(float x, float y) {
        markers = pdfView.visibleAgentProposalMarkers();
        for (int i = 0; i < markers.size(); i++) {
            if (markers.get(i).viewBounds.contains(x, y)) return i;
        }
        return INVALID_ID;
    }

    @Override
    protected void getVisibleVirtualViews(List<Integer> virtualViewIds) {
        markers = pdfView.visibleAgentProposalMarkers();
        for (int i = 0; i < markers.size(); i++) virtualViewIds.add(i);
    }

    @Override
    protected void onPopulateNodeForVirtualView(int virtualViewId, @NonNull AccessibilityNodeInfoCompat node) {
        node.setContentDescription(description);
        if (virtualViewId < 0 || virtualViewId >= markers.size()) {
            // A node must have bounds; this one is gone since the service asked.
            node.setBoundsInParent(new Rect(0, 0, 1, 1));
            return;
        }
        RectF bounds = markers.get(virtualViewId).viewBounds;
        node.setBoundsInParent(new Rect(Math.round(bounds.left), Math.round(bounds.top),
                Math.max(Math.round(bounds.right), Math.round(bounds.left) + 1),
                Math.max(Math.round(bounds.bottom), Math.round(bounds.top) + 1)));
        node.setClickable(true);
        node.setLongClickable(true);
        node.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK);
        node.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_LONG_CLICK);
    }

    @Override
    protected boolean onPerformActionForVirtualView(int virtualViewId, int action, Bundle arguments) {
        if (actions == null || virtualViewId < 0 || virtualViewId >= markers.size()) return false;
        String proposalId = markers.get(virtualViewId).proposalId;
        if (action == AccessibilityNodeInfoCompat.ACTION_CLICK) {
            return actions.onAgentProposalMarkerTap(proposalId);
        }
        if (action == AccessibilityNodeInfoCompat.ACTION_LONG_CLICK) {
            return actions.onAgentProposalMarkerLongPress(proposalId);
        }
        return false;
    }
}
