package com.intellij.codeInsight.lookup.impl;

import com.intellij.codeInsight.CodeInsightSettings;
import com.intellij.codeInsight.completion.CompletionPreferencePolicy;
import com.intellij.codeInsight.completion.CompletionProgressIndicator;
import com.intellij.codeInsight.completion.PrefixMatcher;
import com.intellij.codeInsight.completion.impl.CamelHumpMatcher;
import com.intellij.codeInsight.hint.HintManager;
import com.intellij.codeInsight.hint.HintUtil;
import com.intellij.codeInsight.lookup.*;
import com.intellij.featureStatistics.FeatureUsageTracker;
import com.intellij.ide.IdeEventQueue;
import com.intellij.lang.LangBundle;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.CommandProcessor;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.*;
import com.intellij.openapi.editor.event.*;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.proximity.PsiProximityComparator;
import com.intellij.ui.LightweightHint;
import com.intellij.ui.ListScrollingUtil;
import com.intellij.ui.plaf.beg.BegPopupMenuBorder;
import com.intellij.util.SmartList;
import com.intellij.util.ui.AsyncProcessIcon;
import gnu.trove.THashSet;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.TestOnly;

import javax.swing.*;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.*;
import java.util.List;

public class LookupImpl extends LightweightHint implements Lookup, Disposable {
  private static final Logger LOG = Logger.getInstance("#com.intellij.codeInsight.lookup.impl.LookupImpl");
  private static final int MAX_PREFERRED_COUNT = 5;
  private static final Key<Integer> ITEM_START = Key.create("itemStart");
  static final Object EMPTY_ITEM_ATTRIBUTE = Key.create("emptyItem");

  private static final LookupItem EMPTY_LOOKUP_ITEM = new LookupItem("preselect", "preselect");

  private final Project myProject;
  private final Editor myEditor;
  private final SortedSet<LookupItem> myItems;
  private final SortedMap<LookupItemWeightComparable, SortedSet<LookupItem>> myItemsMap;
  private int myMinPrefixLength;
  private int myPreferredItemsCount;
  private final int myCaretOffset;
  private final LookupItemPreferencePolicy myItemPreferencePolicy;

  private RangeMarker myLookupStartMarker;
  private int myInitialMinPrefixLength = 0;
  private final JList myList;
  private final LookupCellRenderer myCellRenderer;
  private Boolean myPositionedAbove = null;

  private CaretListener myEditorCaretListener;
  private EditorMouseListener myEditorMouseListener;

  private final ArrayList<LookupListener> myListeners = new ArrayList<LookupListener>();

  private boolean myCanceled = true;
  private boolean myDisposed = false;
  private LookupItem myPreselectedItem = EMPTY_LOOKUP_ITEM;
  private boolean myDirty;
  private PsiElement myElement;
  private final AsyncProcessIcon myProcessIcon;
  private final Comparator<? super LookupItem> myComparator;
  private volatile boolean myCalculating;

  public LookupImpl(Project project,
                    Editor editor,
                    LookupItem[] items, LookupItemPreferencePolicy itemPreferencePolicy, final String bottomText){
    super(new JPanel(new BorderLayout()));
    myProject = project;
    myEditor = editor;
    myItemPreferencePolicy = itemPreferencePolicy;
    myCaretOffset = myEditor.getSelectionModel().hasSelection() ? myEditor.getSelectionModel().getSelectionStart() : myEditor.getCaretModel().getOffset();

    final Document document = myEditor.getDocument();
    final PsiFile psiFile = PsiDocumentManager.getInstance(myProject).getPsiFile(document);
    myElement = psiFile == null ? null : psiFile.findElementAt(myEditor.getCaretModel().getOffset());

    final PsiProximityComparator proximityComparator = new PsiProximityComparator(myElement == null ? psiFile : myElement);
    myComparator = new Comparator<LookupItem>() {
      public int compare(LookupItem o1, LookupItem o2) {
        double priority1 = o1.getPriority();
        double priority2 = o2.getPriority();
        if (priority1 > priority2) return -1;
        if (priority2 > priority1) return 1;

        int grouping1 = o1.getGrouping();
        int grouping2 = o2.getGrouping();
        if (grouping1 > grouping2) return -1;
        if (grouping2 > grouping1) return 1;

        int stringCompare = o1.getLookupString().compareToIgnoreCase(o2.getLookupString());
        if (stringCompare != 0) return stringCompare;

        final int proximityCompare = proximityComparator.compare(o1.getObject(), o2.getObject());
        if (proximityCompare != 0) return proximityCompare;

        return o1.hashCode() - o2.hashCode();
      }
    };
    myItems = new TreeSet<LookupItem>(myComparator);
    myItemsMap = new TreeMap<LookupItemWeightComparable, SortedSet<LookupItem>>();

    myProcessIcon = new AsyncProcessIcon("Completion progress");
    myProcessIcon.setVisible(false);
    myList = new JList(new DefaultListModel());
    myCellRenderer = new LookupCellRenderer(this);
    myList.setCellRenderer(myCellRenderer);
    myList.setFixedCellWidth(50);

    for (final LookupItem item : items) {
      addItem(item);
    }

    updateList();
    
    myList.setFocusable(false);

    myList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    myList.setBackground(LookupCellRenderer.BACKGROUND_COLOR);

    JScrollPane scrollPane = new JScrollPane(myList);
    scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
    getComponent().add(scrollPane, BorderLayout.NORTH);
    scrollPane.setBorder(null);

    JPanel bottomPanel = new JPanel(new BorderLayout());

    bottomPanel.add(myProcessIcon, BorderLayout.EAST);
    final JComponent adComponent = HintUtil.createAdComponent(bottomText);
    if (StringUtil.isNotEmpty(bottomText)) {
      adComponent.setPreferredSize(new Dimension(adComponent.getPreferredSize().width, myProcessIcon.getPreferredSize().height));
    }
    bottomPanel.add(adComponent, BorderLayout.CENTER);
    getComponent().add(bottomPanel, BorderLayout.SOUTH);
    getComponent().setBorder(new BegPopupMenuBorder());


    selectMostPreferableItem();
  }

  public AsyncProcessIcon getProcessIcon() {
    return myProcessIcon;
  }

  public void setCalculating(final boolean calculating) {
    myCalculating = calculating;
  }

  public int getPreferredItemsCount() {
    return myPreferredItemsCount;
  }

  public void markDirty() {
    myDirty = true;
    myPreselectedItem = null;
  }

  @TestOnly
  public void resort() {
    final ArrayList<LookupItem> items = new ArrayList<LookupItem>(myItems);
    myDirty = false;
    myPreselectedItem = EMPTY_LOOKUP_ITEM;
    myItemsMap.clear();
    myItems.clear();
    for (final LookupItem item : items) {
      addItem(item);
    }
    updateList();
  }

  public synchronized void addItem(LookupItem item) {
    myItems.add(item);
    item.putUserData(ITEM_START, myCaretOffset - item.getPrefixMatcher().getPrefix().length());
    addItemWeight(item);
    final Icon icon = myCellRenderer.getRawIcon(item);
    if (icon != null) {
      myCellRenderer.updateIconWidth(icon.getIconWidth());
    }

    int maxWidth = myCellRenderer.getMaximumWidth(new LookupItem[]{item});
    myList.setFixedCellWidth(Math.max(maxWidth, myList.getFixedCellWidth()));
  }

  private void addItemWeight(final LookupItem item) {
    final Comparable[] weight = getWeight(myItemPreferencePolicy, myElement, item);
    final LookupItemWeightComparable key = new LookupItemWeightComparable(item.getPriority(), weight);
    SortedSet<LookupItem> list = myItemsMap.get(key);
    if (list == null) myItemsMap.put(key, list = new TreeSet<LookupItem>(myComparator));
    list.add(item);
  }

  public LookupItemPreferencePolicy getItemPreferencePolicy() {
    return myItemPreferencePolicy;
  }

  private static Comparable[] getWeight(final LookupItemPreferencePolicy itemPreferencePolicy, final PsiElement context,
                                        final LookupItem item) {
    if (itemPreferencePolicy instanceof CompletionPreferencePolicy) {
      return ((CompletionPreferencePolicy)itemPreferencePolicy).getWeight(item);
    }
    Comparable i = null;
    if (item.getObject() instanceof PsiElement) {
      i = PsiProximityComparator.getProximity((PsiElement)item.getObject(), context);
    }
    return new Comparable[]{i};
  }

  public int getMinPrefixLength() {
    return myMinPrefixLength;
  }

  int getInitialMinPrefixLength() {
    return myInitialMinPrefixLength;
  }

  public JList getList() {
    return myList;
  }

  public LookupItem[] getItems(){
    return myItems.toArray(new LookupItem[myItems.size()]);
  }

  public final synchronized void updateList() {
    int minPrefixLength = Integer.MAX_VALUE;
    for (final LookupItem item : myItems) {
      minPrefixLength = Math.min(item.getPrefixMatcher().getPrefix().length(), minPrefixLength);
    }
    myMinPrefixLength = minPrefixLength;
    if (!myDirty) {
      myInitialMinPrefixLength = minPrefixLength;
    }

    Object oldSelected = !myDirty ? null : myList.getSelectedValue();
    DefaultListModel model = (DefaultListModel)myList.getModel();
    model.clear();

    ArrayList<LookupItem> allItems = new ArrayList<LookupItem>();
    Set<LookupItem> firstItems = new THashSet<LookupItem>();

    final boolean hasPreselectedItem = !myDirty && myPreselectedItem != EMPTY_LOOKUP_ITEM;
    boolean addPreselected = hasPreselectedItem;

    for (final LookupItemWeightComparable comparable : myItemsMap.keySet()) {
      final List<LookupItem> suitable = new SmartList<LookupItem>();
      for (final LookupItem item : myItemsMap.get(comparable)) {
        if (item.isPrefixMatched()) {
          suitable.add(item);
        }
      }

      if (allItems.size() + suitable.size() + (addPreselected ? 1 : 0) > MAX_PREFERRED_COUNT) break;
      for (final LookupItem item : suitable) {
        allItems.add(item);
        firstItems.add(item);
        model.addElement(item);

        if (hasPreselectedItem && item == myPreselectedItem) {
          addPreselected = false;
        }
      }
    }
    if (addPreselected) {
      allItems.add(myPreselectedItem);
      firstItems.add(myPreselectedItem);
      model.addElement(myPreselectedItem);
    }

    myPreferredItemsCount = allItems.size();

    for (LookupItem<?> item : myItems) {
      if (!firstItems.contains(item) && item.isPrefixMatched()) {
        model.addElement(item);
        allItems.add(item);
      }
    }
    boolean isEmpty = allItems.isEmpty();
    if (isEmpty) {
      LookupItem<String> item = new LookupItem<String>(myCalculating ? " " : LangBundle.message("completion.no.suggestions"), "                       ");
      item.setPrefixMatcher(new CamelHumpMatcher(""));
      item.setAttribute(EMPTY_ITEM_ATTRIBUTE, "");
      model.addElement(item);
      allItems.add(item);
    }
    myList.setFixedCellHeight(myCellRenderer.getListCellRendererComponent(myList, myList.getModel().getElementAt(0), 0, false, false).getPreferredSize().height);

    myList.setVisibleRowCount(Math.min(myList.getModel().getSize(), CodeInsightSettings.getInstance().LOOKUP_HEIGHT));

    if (!isEmpty) {
      if (oldSelected != null) {
        if (!ListScrollingUtil.selectItem(myList, oldSelected)) {
          selectMostPreferableItem();
        }
      } else {
        if (myPreselectedItem == EMPTY_LOOKUP_ITEM) {
          selectMostPreferableItem();
          myPreselectedItem = getCurrentItem();
        } else if (hasPreselectedItem) {
          ListScrollingUtil.selectItem(myList, myPreselectedItem);
        } else {
          selectMostPreferableItem();
        }
      }
    }
  }

  /**
   * @return point in layered pane coordinate system.
   */
  public Point calculatePosition(){
    Dimension dim = getComponent().getPreferredSize();
    int lookupStart = getLookupStart();
    LogicalPosition pos = myEditor.offsetToLogicalPosition(lookupStart);
    Point location = myEditor.logicalPositionToXY(pos);
    location.y += myEditor.getLineHeight();
    JComponent editorComponent = myEditor.getComponent();
    JComponent internalComponent = myEditor.getContentComponent();
    JLayeredPane layeredPane = editorComponent.getRootPane().getLayeredPane();
    Point layeredPanePoint=SwingUtilities.convertPoint(internalComponent,location, layeredPane);
    layeredPanePoint.x -= myCellRenderer.getIconIndent();
    if (dim.width > layeredPane.getWidth()){
      dim.width = layeredPane.getWidth();
    }
    int wshift = layeredPane.getWidth() - (layeredPanePoint.x + dim.width);
    if (wshift < 0){
      layeredPanePoint.x += wshift;
    }

    int shiftLow = layeredPane.getHeight() - (layeredPanePoint.y + dim.height);
    int shiftHigh = layeredPanePoint.y - dim.height;
    myPositionedAbove = shiftLow < 0 && shiftLow < shiftHigh ? Boolean.TRUE : Boolean.FALSE;

    if (myPositionedAbove.booleanValue()){
      layeredPanePoint.y -= dim.height + myEditor.getLineHeight();
    }
    return layeredPanePoint;
  }

  public void finishLookup(final char completionChar){
    final LookupItem item = (LookupItem)myList.getSelectedValue();
    if (item == null){
      fireItemSelected(null, completionChar);
      hide();
      return;
    }

    if(item.getObject() instanceof DeferredUserLookupValue) {
      if(!((DeferredUserLookupValue)item.getObject()).handleUserSelection(item,myProject)) {
        fireItemSelected(null, completionChar);
        hide();
        return;
      }
    }

    final String s = item.getLookupString();
    if (item.getAttribute(EMPTY_ITEM_ATTRIBUTE) != null){
      fireItemSelected(null, completionChar);
      hide();
      return;
    }

    myCanceled = false;
    hide();
    final CompletionProgressIndicator indicator = CompletionProgressIndicator.getCurrentCompletion();
    if (indicator != null) {
      indicator.cancel();
    }

    ApplicationManager.getApplication().runWriteAction(
        new Runnable() {
          public void run(){
            int lookupStart = item.getUserData(ITEM_START).intValue();
            //SD - start
            //this patch fixes the problem, that template is finished after showing lookup
            LogicalPosition lookupPosition = myEditor.offsetToLogicalPosition(lookupStart);
            myEditor.getCaretModel().moveToLogicalPosition(lookupPosition);
            //SD - end

            if (myEditor.getSelectionModel().hasSelection()){
              myEditor.getDocument().deleteString(myEditor.getSelectionModel().getSelectionStart(), myEditor.getSelectionModel().getSelectionEnd());
            }
            if (myMinPrefixLength > 0){
              FeatureUsageTracker.getInstance().triggerFeatureUsed("editing.completion.camelHumps");
              myEditor.getDocument().deleteString(lookupStart, lookupStart + myMinPrefixLength);
            }
            myEditor.getDocument().insertString(lookupStart, s);

            int offset = lookupStart + s.length();
            myEditor.getCaretModel().moveToOffset(offset);
            myEditor.getScrollingModel().scrollToCaret(ScrollType.RELATIVE);
            myEditor.getSelectionModel().removeSelection();
            fireItemSelected(item, completionChar);
          }
        }
    );
  }

  private int getLookupStart() {
    return myLookupStartMarker != null ? myLookupStartMarker.getStartOffset() : calcLookupStart();
  }

  public void show(){
    int lookupStart = calcLookupStart();
    myLookupStartMarker = myEditor.getDocument().createRangeMarker(lookupStart, lookupStart);
    myLookupStartMarker.setGreedyToLeft(true);

    myEditor.getDocument().addDocumentListener(new DocumentAdapter() {
      public void documentChanged(DocumentEvent e) {
        if (!myLookupStartMarker.isValid()){
          hide();
        }
      }
    }, this);

    myEditorCaretListener = new CaretListener() {
      public void caretPositionChanged(CaretEvent e){
        int curOffset = myEditor.getCaretModel().getOffset();
        final LookupItem item = getCurrentItem();
        if (item == null || item == EMPTY_LOOKUP_ITEM) return;

        if (curOffset != item.getUserData(ITEM_START).intValue() + item.getPrefixMatcher().getPrefix().length()) {
          hide();
        }
      }
    };
    myEditor.getCaretModel().addCaretListener(myEditorCaretListener);

    myEditorMouseListener = new EditorMouseAdapter() {
      public void mouseClicked(EditorMouseEvent e){
        e.consume();
        hide();
      }
    };
    myEditor.addEditorMouseListener(myEditorMouseListener);

    myList.addListSelectionListener(new ListSelectionListener() {
      public void valueChanged(ListSelectionEvent e){
        LookupItem item = (LookupItem)myList.getSelectedValue();
        if (item != null && item.getAttribute(EMPTY_ITEM_ATTRIBUTE) != null){
          item = null;
        }
        fireCurrentItemChanged(item);
      }
    });

    myList.addMouseListener(new MouseAdapter() {
      public void mouseClicked(MouseEvent e){
        if (e.getClickCount() == 2){
          CommandProcessor.getInstance().executeCommand(myProject, new Runnable() {
            public void run() {
              finishLookup(NORMAL_SELECT_CHAR);
            }
          }, "", null);
        }
      }
    });

    if (ApplicationManager.getApplication().isUnitTestMode()) return;

    Point p = calculatePosition();
    HintManager hintManager = HintManager.getInstance();
    hintManager.showEditorHint(this, myEditor, p, HintManager.HIDE_BY_ESCAPE | HintManager.UPDATE_BY_SCROLLING, 0, false);
  }

  private int calcLookupStart() {
    int offset = myEditor.getSelectionModel().hasSelection()
                 ? myEditor.getSelectionModel().getSelectionStart()
                 : myEditor.getCaretModel().getOffset();
    return offset - myMinPrefixLength;
  }

  private void selectMostPreferableItem(){
    final int index = doSelectMostPreferableItem(myItemPreferencePolicy, ((DefaultListModel)myList.getModel()).toArray());
    myList.setSelectedIndex(index);

    if (index >= 0 && index < myList.getModel().getSize()){
      ListScrollingUtil.selectItem(myList, index);
    }
    else if (!myItems.isEmpty()) {
      ListScrollingUtil.selectItem(myList, 0);
    }
  }

  @Nullable
  public LookupItem getCurrentItem(){
    LookupItem item = (LookupItem)myList.getSelectedValue();
    if (item != null && item.getAttribute(EMPTY_ITEM_ATTRIBUTE) != null){
      return null;
    }
    return item;
  }

  public void setCurrentItem(LookupItem item){
    ListScrollingUtil.selectItem(myList, item);
  }

  public void addLookupListener(LookupListener listener){
    myListeners.add(listener);
  }

  public void removeLookupListener(LookupListener listener){
    myListeners.remove(listener);
  }

  public Rectangle getCurrentItemBounds(){
    int index = myList.getSelectedIndex();
    Rectangle itmBounds = myList.getCellBounds(index, index);
    if (itmBounds == null){
      return null;
    }
    Rectangle listBounds=myList.getBounds();
    final JRootPane pane = myList.getRootPane();
    if (pane == null) {
      LOG.assertTrue(false, myItems);
    }
    JLayeredPane layeredPane= pane.getLayeredPane();
    Point layeredPanePoint=SwingUtilities.convertPoint(myList,listBounds.x,listBounds.y,layeredPane);
    itmBounds.x = layeredPanePoint.x;
    itmBounds.y = layeredPanePoint.y;
    return itmBounds;
  }

  public void fireItemSelected(final LookupItem item, char completionChar){
    PsiDocumentManager.getInstance(myProject).commitAllDocuments();

    if (item != null && myItemPreferencePolicy != null){
      myItemPreferencePolicy.itemSelected(item, this);
    }

    if (!myListeners.isEmpty()){
      LookupEvent event = new LookupEvent(this, item, completionChar);
      LookupListener[] listeners = myListeners.toArray(new LookupListener[myListeners.size()]);
      for (LookupListener listener : listeners) {
        listener.itemSelected(event);
      }
    }
  }

  private void fireLookupCanceled(){
    if (!myListeners.isEmpty()){
      LookupEvent event = new LookupEvent(this, null);
      LookupListener[] listeners = myListeners.toArray(new LookupListener[myListeners.size()]);
      for (LookupListener listener : listeners) {
        listener.lookupCanceled(event);
      }
    }
  }

  private void fireCurrentItemChanged(LookupItem item){
    if (!myListeners.isEmpty()){
      LookupEvent event = new LookupEvent(this, item);
      LookupListener[] listeners = myListeners.toArray(new LookupListener[myListeners.size()]);
      for (LookupListener listener : listeners) {
        listener.currentItemChanged(event);
      }
    }
  }

  private static int divideString(String lookupString, PrefixMatcher matcher) {
    for (int i = matcher.getPrefix().length(); i <= lookupString.length(); i++) {
      if (matcher.prefixMatches(lookupString.substring(0, i))) {
        return i;
      }
    }
    return -1;
  }

  public boolean fillInCommonPrefix(boolean toCompleteUniqueName){
    if (myDirty) return false;

    ListModel listModel = myList.getModel();
    if (listModel.getSize() <= 1) return false;

    boolean isStrict = false;
    if (listModel.getSize() == 0) return false;

    final LookupItem firstItem = (LookupItem)listModel.getElementAt(0);
    if (listModel.getSize() == 1 && firstItem.getAttribute(EMPTY_ITEM_ATTRIBUTE) != null) return false;

    final PrefixMatcher matcher = firstItem.getPrefixMatcher();
    String lookupString = firstItem.getLookupString();
    int div = divideString(lookupString, matcher);
    if (div < 0) return false;

    String beforeCaret = lookupString.substring(0, div);
    String afterCaret = lookupString.substring(div);
    String presentPrefix = matcher.getPrefix();

    for (int i = 1; i < listModel.getSize(); i++) {
      LookupItem item = (LookupItem)listModel.getElementAt(i);
      if (!presentPrefix.equals(item.getPrefixMatcher().getPrefix())) return false;

      lookupString = item.getLookupString();
      div = divideString(lookupString, item.getPrefixMatcher());
      if (div < 0) return false;

      String _afterCaret = lookupString.substring(div);
      if (beforeCaret != null) {
        if (div != beforeCaret.length() || !lookupString.startsWith(beforeCaret)) beforeCaret = null;
      }

      while (afterCaret.length() > 0) {
        if (_afterCaret.startsWith(afterCaret)) {
          if (_afterCaret.length() > afterCaret.length()){
            isStrict = true;
          }
          break;
        }
        afterCaret = afterCaret.substring(0, afterCaret.length() - 1);
        isStrict = true;
      }
      if (afterCaret.length() == 0) return false;
    }

    if (!isStrict && !toCompleteUniqueName) return false;

    final LookupItem curItem = getCurrentItem();

    int offset = myEditor.getCaretModel().getOffset();
    if (beforeCaret != null) { // correct case, expand camel-humps
      curItem.setPrefixMatcher(curItem.getPrefixMatcher().cloneWithPrefix(beforeCaret));
      myEditor.getDocument().replaceString(offset - presentPrefix.length(), offset, beforeCaret);
      presentPrefix = beforeCaret;
    }

    offset = myEditor.getCaretModel().getOffset();
    int i = 0;
    final CharSequence text = myEditor.getDocument().getCharsSequence();
    while (offset + i < text.length() && i < afterCaret.length() && text.charAt(offset + i) == afterCaret.charAt(i)) i++;
    curItem.setPrefixMatcher(curItem.getPrefixMatcher().cloneWithPrefix(presentPrefix + afterCaret.substring(0, i)));
    myEditor.getCaretModel().moveToOffset(offset + i);

    for (final LookupElement item : myItems) {
      assert item.setPrefixMatcher(item.getPrefixMatcher().cloneWithPrefix(presentPrefix + afterCaret));
    }
    EditorModificationUtil.insertStringAtCaret(myEditor, afterCaret.substring(i));

    updateList();
    return true;
  }

  public PsiFile getPsiFile() {
    return PsiDocumentManager.getInstance(myEditor.getProject()).getPsiFile(myEditor.getDocument());
  }

  public boolean isCompletion() {
    return myItemPreferencePolicy instanceof CompletionPreferencePolicy;
  }

  public PsiElement getPsiElement() {
    PsiFile file = getPsiFile();
    if (file == null) return null;

    int offset = getEditor().getCaretModel().getOffset();
    if (offset > 0) return file.findElementAt(offset - 1);

    return file.findElementAt(offset + 1);
  }

  public Editor getEditor() {
    return myEditor;
  }

  public boolean isPositionedAboveCaret(){
    return myPositionedAbove != null && myPositionedAbove.booleanValue();
  }

  public void hide(){
    ApplicationManager.getApplication().assertIsDispatchThread();

    if (IdeEventQueue.getInstance().getPopupManager().closeActivePopup()) return;

    if (myDisposed) return;

    super.hide();

    Disposer.dispose(this);

    if (myCanceled){
      fireLookupCanceled();
    }
  }

  public void dispose() {
    assert !myDisposed;
    myDisposed = true;
    myProcessIcon.dispose();
    if (myEditorCaretListener != null) {
      myEditor.getCaretModel().removeCaretListener(myEditorCaretListener);
    }
    if (myEditorMouseListener != null) {
      myEditor.removeEditorMouseListener(myEditorMouseListener);
    }
  }

  public static int doSelectMostPreferableItem(final LookupItemPreferencePolicy itemPreferencePolicy, Object[] items) {
    if (itemPreferencePolicy == null){
      return -1;
    }
    else{
      LookupItem prefItem = null;
      int prefItemIndex = -1;

      for(int i = 0; i < items.length; i++){
        LookupItem item = (LookupItem)items[i];
        final Object obj = item.getObject();
        if (obj instanceof PsiElement && !((PsiElement)obj).isValid()) continue;
        if (prefItem == null){
          prefItem = item;
          prefItemIndex = i;
        }
        else{
          int d = itemPreferencePolicy.compare(item, prefItem);
          if (d < 0){
            prefItem = item;
            prefItemIndex = i;
          }
        }
      }
      return prefItem != null ? prefItemIndex : -1;
    }
  }

  public void adaptSize() {
    if (isVisible()) {
      HintManager.getInstance().adjustEditorHintPosition(this, myEditor, getComponent().getLocation());
    }
  }

  public LookupItem[] getSortedItems() {
    final LookupItem[] result = new LookupItem[myList.getModel().getSize()];
    ((DefaultListModel)myList.getModel()).copyInto(result);
    return result;
  }
}
