// Simplified snapshottable trees from C5
// sestoft@itu.dk * 2010-06-04, 2010-10-31, 2011-02-28

// The primary problem here is probably the verification of the
// implementation (especially when using node copy persistence) rather
// than the specification of the operations (the mathematical model).
// The model can be "functional" in the sense that updating the tree
// does not affect any of its snapshots, seen from the model.

// There are two orthogonal dimensions, A and B:
// A1 = no rebalancing, A2 = rebalancing
// B1 = path copy persistence, B2 = node copy persistence (used in C5).

// A1+B1: No mutable shared data in the implementation.  This may
// still be a good case study, because it is "obviously correct" and
// hence ought be easy to prove correct wrt the model.

// A2+B1: Mutation of shared data (due to rebalancing rotations), not
// observable in the model.  Has this been done in the Ynot B-tree
// case study (Malecha)?  How are cursors in the tree (iterators in
// use at the time of the rebalancing) affected by rebalancing
// operations?

// A1+B2: Mutation of shared data (due to updates of nodes' third
// pointer), not observable in the model.

// A2+B2: Mutation of shared data (due to rotations, and due to
// updates of nodes' third pointer), not observable in the model.
// Maybe a challenge to prove the correctness of rebalancing
// operations because they must respect the node copy persistence
// invariants.

// ------------------------------------------------------------

// Below is the first attempt at a simplified implementation: A1+B1 =
// path copy persistence without rebalancing.  Hence no need for
// colors.  Yet complicated enough I think.

// Operations on interface ITree and all Tree implementations:

// * tree.add(item) attempts to add the item to the tree and returns
//   true if the item was added; otherwise if the item is already in
//   the tree, does nothing and returns false.
//
// * tree.contains(item) returns true if the item is in the tree,
//   otherwise false
//
// * tree.get(i) return the item at index i>=0 in the ordered tree
//
// * tree.snapshot(item) returns a new tree that is a readonly
//   snapshot of the given tree.  Updates to the given tree will not 
//   affect the snapshot.
//
// * tree.iterator() returns an iterator (enumerator, stream) of the
//   tree items.

// The reason for including the somewhat complicated iterator()
// operation is that it makes the distinction between a tree and its
// snapshots completely clear: While it is illegal to add to a tree
// while iterating over it (must throw
// ConcurrentModificationException), it is perfectly legal to modify
// the tree while iterating over one of its snapshots.  Also, this
// poses an additional verification challenge when considering the
// rebalancing (case A2+B1) because it add(item) on the tree may
// rebalance it in the middle of an iteration over a snapshot, and
// that should be legal and not affect the iteration.  I believe that
// holds of this implementation.

// The get(i) operation may be used instead of the iterator to show
// that snapshots are unaffected by updates to the live tree.

// Must run with 
//   java -ea SnapshotTrees

import java.util.*;

interface ITree extends Iterable<Integer> {
  public boolean contains(int item);
  public boolean add(int item);
  public int get (int i);
  public ITree snapshot();
  public Iterator<Integer> iterator();
}

class A1B1Tree implements ITree {
  private Node root;            // Is null for empty set
  private boolean isSnapshot;   // Is this a readonly snapshot?
  private boolean hasSnapshot;  // Does this readwrite tree have a snapshot?
  public int stamp;             // For fail-early enumeration

  private static class Node {
    public int item;
    public Node left;
    public Node rght;
      
    //public Node(int item) {
    //  this.item = item;
    //}

    public Node(Node left, int item, Node rght) {
      this.left = left;
      this.item = item;
      this.rght = rght;
    }

    public String toString() {
      return "[" + (left==null ? "_" : left.toString()) + "," 
        + item +   (rght==null ? "_" : rght.toString()) + "]";
    }
  }

  //public A1B1Tree() { }

  private A1B1Tree(A1B1Tree tree) { 
    root = tree.root;
    isSnapshot = true;
  }

  public boolean contains(int item) {
    Node node = root;
    boolean found = false;
    while (!found && node != null) 
      if (item < node.item)
        node = node.left;
      else if (node.item < item)
        node = node.rght;
      else
        found = true;
    return found;
  }

  public int get(int i) {
    return getNode(root, i);
  }

  private static int count(Node n) {
    int res;
    if (n == null)
      res = 0;
    else 
      res = count(n.left) + 1 + count(n.rght);
    return res;
  }
  
  // This is extremely inefficient (if the tree is unbalanced) because
  // the count() is computed repeatedly; it may be improved by caching
  // count(node) in each node, but that requires a new representation
  // invariant.  Also, with node copy persistence (the A1B2 variant of
  // snapshottable trees) there is no easy way to maintain node counts
  // in snapshots: the same node may be part of multiple snapshots.
  
  private static int getNode(Node n, int i) {
    int leftCount = count(n.left);      // Throws if set empty
    while (i != leftCount) {
      if (i < leftCount) 
        n = n.left;                     // Doesn't throw 
      else { // i > leftCount
        n = n.rght;                     // Doesn't throw
        i = i - leftCount - 1;
      }
      leftCount = count(n.left);        // Throws if i out of range
    }
    return n.item;                      // Doesn't throw
  }
  
  public boolean add(int item) {
    RefBool updated = new RefBool();    
    if (isSnapshot)
      throw new RuntimeException("Illegal to add to snapshot");
    else 
      root = addRecursive(root, item, updated);
    return updated.value;
  }

  private Node addRecursive(Node node, int item, RefBool updated) {
    Node res = node;
    if (node == null) {
      updated.value = true;
      stamp++;
      res = new Node(item);
    } else
      // If the tree has a snapshot and a node was updated we must
      // replicate the path back to the root, to not affect snapshots
      if (item < node.item) {
        Node newLeft = addRecursive(node.left, item, updated);
        if (hasSnapshot && updated.value) 
          res = new Node(newLeft, node.item, node.rght);
        else   // Either no snapshot or newLeft==node.left already:
          node.left = newLeft;    
      } else if (node.item < item) {
        Node newRght = addRecursive(node.rght, item, updated);
        if (hasSnapshot && updated.value) 
          res = new Node(node.left, node.item, newRght);
        else   // Either no snapshot or newRght==node.rght already:
          node.rght = newRght;
      } // else item == node.item so no update.
    return res;
  }

  public ITree snapshot() {
    if (isSnapshot)
      throw new RuntimeException("Illegal to snapshot a snapshot");
    else 
      hasSnapshot = true;
    return new A1B1Tree(this);
  }

  public Iterator<Integer> iterator() {
    return new TreeIterator(this, stamp);
  }

  public String toString() {
    return root.toString();
  }

  class TreeIterator implements Iterator<Integer> {
    private final int oldStamp;
    // The stack contains the path from tree.root to peek().node.
    // The top node peek().node holds the next item to yield.
    // All items in the subtree at node.left have been yielded already.
    // If node.yielded then the item in the node itself has been yielded.
    // Outside of method next(), peek().yielded is false.

    // The sequence of items represented by a given stack (which is
    // the same as the remaining items in the iterator) can be defined
    // as follows.  Define, for a node reference:
    //   inorder(null) = [] 
    //   inorder(Node(item,left,rght)) = inorder(left) @ [item] @ inorder(rght)
    // and for a nodestate ns:
    //   nodeitems(ns) = if ns.yielded then [] else ns.node.item :: inorder(ns.node.rght)
    // and for a stack of node states nss with the stack top as first item:
    //   stackitems(nss) = concat (map nodeitems nss)

    private final Stack<NodeState> context = new Stack<NodeState>();
    
    public TreeIterator(A1B1Tree tree, int oldStamp) {
      pushLeftPath(tree.root);
      this.oldStamp = oldStamp;
    }

    private void pushLeftPath(Node node) {
      while (node != null) {
        context.push(new NodeState(node));
        node = node.left;
      }
    }

    public boolean hasNext() {
      return !context.empty();
    }

    public Integer next() {
      Integer result;
      if (stamp != oldStamp)  // Never the case if this.isSnapshot
        throw new ConcurrentModificationException("Tree was modified during iteration");
      if (hasNext()) {
        NodeState nodeState = context.peek();
        result = nodeState.node.item;
        nodeState.yielded = true;
        if (nodeState.node.rght != null) 
          pushLeftPath(nodeState.node.rght);
        else 
          while (!context.empty() && context.peek().yielded) 
            context.pop();
      } else
        throw new Error("Iterator: No more items");
      return result;
    }

    public void remove() {      // Silly Java design mistake
      throw new Error("remove not implemented");
    }

    private class NodeState {
      public final Node node;
      public boolean yielded;
      
      public NodeState(Node node) {
        this.node = node;
        this.yielded = false;
      }
    }
  }
}

class RefBool {
  public boolean value;
}
/*
class SnapshotTrees {
  public static void main(String[] args) {
    exerciseTree(new A1B1Tree());
    testSnapshotsIncreasing(new A1B1Tree());
    testSnapshotsDecreasing(new A1B1Tree());
    testSnapshotsRandom(new A1B1Tree());
    System.out.println("Tests successful");
  }

  private static void hasItems(ITree tree, int[] items) {
    for (int i=0; i<items.length; i++) 
      assert(tree.get(i) == items[i]);
  }

  private static void hasItems(ITree tree, Collection<Integer> coll) {
    int[] arr = new int[coll.size()];
    int i = 0;
    for (int item : coll)
      arr[i++] = item;
    hasItems(tree, arr);
  }


  private static void exerciseTree(ITree tree) {
    printTree(tree);
    assert(tree.add(5)); 
    assert(tree.add(2)); 
    assert(tree.add(11)); 
    assert(tree.add(3)); 
    assert(tree.add(7)); 
    assert(tree.add(13)); 
    assert(tree.get(1) == 3);
    assert(!tree.add(13)); 
    assert(!tree.add(5)); 
    hasItems(tree, new int[] { 2, 3, 5, 7, 11, 13 });
    assert(tree.contains(5));
    assert(tree.contains(2));
    assert(tree.contains(13));
    assert(!tree.contains(4));
    printTree(tree);
    ITree snap1 = tree.snapshot();
    hasItems(snap1, new int[] { 2, 3, 5, 7, 11, 13 });
    printTree(snap1);
    assert(tree.add(17));
    hasItems(tree, new int[] { 2, 3, 5, 7, 11, 13, 17 });
    printTree(snap1);
    hasItems(snap1, new int[] { 2, 3, 5, 7, 11, 13 });
    printTree(tree);
    ITree snap2 = tree.snapshot();
    hasItems(snap2, new int[] { 2, 3, 5, 7, 11, 13, 17 });
    assert(tree.add(6));
    assert(tree.add(4));
    hasItems(tree, new int[] { 2, 3, 4, 5, 6, 7, 11, 13, 17 });
    printTree(snap1);
    hasItems(snap1, new int[] { 2, 3, 5, 7, 11, 13 });
    printTree(snap2);
    hasItems(snap2, new int[] { 2, 3, 5, 7, 11, 13, 17 });
    printTree(tree);
    for (int i : snap1) 
      tree.add(2 * i);
    hasItems(tree, new int[] { 2, 3, 4, 5, 6, 7, 10, 11, 13, 14, 17, 22, 26  });
    printTree(tree);
    boolean concurrentmodificationexception = false;
    try {
      for (int i : tree) 
        tree.add(2 * i);
    } catch (ConcurrentModificationException exn) {
      concurrentmodificationexception = true;
    }
    if (concurrentmodificationexception)
      System.out.println("Successfully threw ConcurrentModificationException");
    else
      System.out.println("ERROR: Failed to throw ConcurrentModificationException");
  }

  private static void testSnapshotsIncreasing(ITree tree) {
    ArrayList<ITree> snapshots = new ArrayList<ITree>();
    final int count = 34;
    for (int item=0; item<count; item++) {
      snapshots.add(tree.snapshot());
      assert snapshots.size()==item+1;
      for (int i=0; i<snapshots.size(); i++) {
        for (int j=0; j<i; j++) {
          assert snapshots.get(i).contains(j) : "item="+item+" i="+i+" j="+j;
          assert snapshots.get(i).get(j)==j;
        }
        assert !snapshots.get(i).contains(i);
      }
      tree.add(item);
      for (int i=0; i<=item; i++)
        assert tree.contains(i);
      for (int i=0; i<snapshots.size(); i++) {
        for (int j=0; j<i; j++)
          assert snapshots.get(i).contains(j);
        assert !snapshots.get(i).contains(i);
      }
    }
  }
  
  private static void testSnapshotsDecreasing(ITree tree) {
    ArrayList<ITree> snapshots = new ArrayList<ITree>();
    final int count = 34;
    for (int item=count; item>=0; item--) {
      snapshots.add(tree.snapshot());
      assert snapshots.size()==count-item+1;
      for (int i=0; i<snapshots.size(); i++) {
        for (int j=0; j<i; j++) {
          assert snapshots.get(i).contains(count-j) : "item="+item+" i="+i+" j="+j;
          assert snapshots.get(i).get(j)==count-i+1+j;
        }
        assert !snapshots.get(i).contains(count-i);
      }
      tree.add(item);
      for (int i=item; i<=count; i++)
        assert tree.contains(i);
      for (int i=0; i<snapshots.size(); i++) {
        for (int j=0; j<i; j++)
          assert snapshots.get(i).contains(count-j) : "item="+item+" i="+i+" j="+j;
        assert !snapshots.get(i).contains(count-i);
      }
    }
  }

  private final static Random rnd = new Random();

  private static void testSnapshotsRandom(ITree tree) {
    final int count=150;
    ArrayList<ITree> snapshots = new ArrayList<ITree>();
    ArrayList<Set<Integer>> sets = new ArrayList<Set<Integer>>();
    int newItem = -1;
    for (int round=0; round<count; round++) {
      snapshots.add(tree.snapshot());
      Set<Integer> set = new TreeSet<Integer>();
      if (round>0) {
        set.add(newItem);
        set.addAll(sets.get(round-1));
      }
      sets.add(set);
      hasItems(tree, set);
      assert snapshots.size()==sets.size();
      for (int i=0; i<snapshots.size(); i++) {
        for (int item : sets.get(i))
          assert snapshots.get(i).contains(item) : "i="+i+" item="+item;
        for (int item : snapshots.get(i)) 
          assert sets.get(i).contains(item);
        hasItems(snapshots.get(i), sets.get(i));
      }
      newItem = rnd.nextInt(2*count);
      tree.add(newItem);
      for (int i=0; i<snapshots.size(); i++) {
        for (int item : sets.get(i))
          assert snapshots.get(i).contains(item);
        for (int item : snapshots.get(i)) 
          assert sets.get(i).contains(item);
      }
    }
  }

  private static void printTree(ITree tree) {
    for (int i : tree)
      System.out.print(i + " ");
    System.out.println();
  }
}
*/