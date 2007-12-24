package com.intellij.codeInspection.ui;

import com.intellij.codeInspection.CommonProblemDescriptor;
import com.intellij.codeInspection.ex.InspectionTool;
import com.intellij.codeInspection.reference.RefElement;
import com.intellij.codeInspection.reference.RefEntity;
import com.intellij.codeInspection.reference.RefUtil;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.vcs.FileStatus;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.tree.MutableTreeNode;

/**
 * @author max
 */
public class RefElementNode extends InspectionTreeNode {
  private static final Logger LOG = Logger.getInstance("#com.intellij.codeInspection.ui.RefElementNode");
  private boolean myHasDescriptorsUnder = false;
  private CommonProblemDescriptor mySingleDescriptor = null;
  protected InspectionTool myTool;

  public RefElementNode(final Object userObject, final InspectionTool tool) {
    super(userObject);
    myTool = tool;
  }

  public RefElementNode(RefElement element, final InspectionTool inspectionTool) {
    super(element);
    myTool = inspectionTool;
    LOG.assertTrue(element != null);
  }

  public boolean hasDescriptorsUnder() { return myHasDescriptorsUnder; }

  @Nullable
  public RefEntity getElement() {
    return (RefEntity)getUserObject();
  }

  @Nullable
  public Icon getIcon(boolean expanded) {
    final RefEntity refEntity = getElement();
    if (refEntity == null) {
      return null;
    }
    return refEntity.getIcon(expanded);
  }

  public int getProblemCount() {
    return Math.max(1, super.getProblemCount());
  }

  public String toString() {
    final RefEntity element = getElement();
    if (element == null) {
      return "";
    }
    return RefUtil.getInstance().getQualifiedName(element.getRefManager().getRefinedElement(element));
  }

  public boolean isValid() {
    final RefEntity refEntity = getElement();
    return refEntity != null && refEntity.isValid();
  }

  public boolean isResolved() {
    return myTool.isElementIgnored(getElement());
  }


  public void ignoreElement() {
    myTool.ignoreCurrentElement(getElement());
    super.ignoreElement();
  }

  public void amnesty() {
    myTool.amnesty(getElement());
    super.amnesty();
  }

  public FileStatus getNodeStatus() {
    return  myTool.getElementStatus(getElement());    
  }

  public void add(MutableTreeNode newChild) {
    super.add(newChild);
    if (newChild instanceof ProblemDescriptionNode) {
      myHasDescriptorsUnder = true;
    }
  }

  public void setProblem(CommonProblemDescriptor descriptor) {
    mySingleDescriptor = descriptor;
  }

  public CommonProblemDescriptor getProblem() {
    return mySingleDescriptor;
  }

}
