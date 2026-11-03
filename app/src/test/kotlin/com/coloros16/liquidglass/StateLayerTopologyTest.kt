package com.coloros16.liquidglass

import com.coloros16.liquidglass.hook.StateLayerTopology as Topology
import org.junit.Assert.*
import org.junit.Test

class StateLayerTopologyTest {
    private class Node(var children:List<Node>?=emptyList())
    private fun edges(value:Any)= (value as Node).children

    @Test fun wholeContainerMustNeverBecomeItsOwnBaseLayer() {
        val state=Node()
        assertFalse(Topology.canWrap(state,state,::edges))
        val wrapper=Node(listOf(state))
        assertFalse(Topology.canWrap(state,wrapper,::edges))
    }
    @Test fun onlyTheNativeChildIsWrappedAndOtherLayersAreUnchanged() {
        val fill=Node();val touch=Node();val state=Node(listOf(fill,touch))
        assertTrue(Topology.canWrap(state,fill,::edges))
        val wrapper=Node(listOf(fill));state.children=listOf(wrapper,touch)
        assertTrue(Topology.canWrap(state,wrapper,::edges))
        assertSame(touch,state.children!![1])
    }
    @Test fun preexistingIndirectCyclesAreRejected() {
        val a=Node();val b=Node(listOf(a));a.children=listOf(b)
        assertFalse(Topology.canWrap(Node(),a,::edges))
    }
    @Test fun sharedAcyclicChildrenAreSafe() {
        val leaf=Node();val graph=Node(listOf(Node(listOf(leaf)),Node(listOf(leaf))))
        assertTrue(Topology.canWrap(Node(),graph,::edges))
    }
    @Test fun unknownOrUnboundedGraphsAreNotLeased() {
        assertFalse(Topology.canWrap(Node(),Node(null),::edges))
        var graph=Node();repeat(64) {graph=Node(listOf(graph))}
        assertFalse(Topology.canWrap(Node(),graph,::edges))
        assertFalse(Topology.canWrap(Node(),Node(),::edges,0))
    }
    @Test fun cleanupCannotOverwriteAThemeReplacementOrDifferentContainer() {
        val state=Node();val wrapper=Node()
        assertTrue(Topology.canRestore(state,state,wrapper,wrapper))
        assertFalse(Topology.canRestore(state,Node(),wrapper,wrapper))
        assertFalse(Topology.canRestore(state,state,Node(),wrapper))
        assertFalse(Topology.canRestore(state,null,wrapper,wrapper))
    }
}
