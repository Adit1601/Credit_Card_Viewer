package com.cardvault.ui.home

import android.os.Bundle
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SearchView
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cardvault.R
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar

class HomeFragment : Fragment(R.layout.fragment_home) {

    private val viewModel: CardViewModel by viewModels()

    private lateinit var toolbar: MaterialToolbar
    private lateinit var recycler: RecyclerView
    private lateinit var emptyState: View
    private lateinit var noMatches: View
    private lateinit var adapter: CardTileAdapter

    private var itemTouchHelper: ItemTouchHelper? = null
    private var reorderMode: Boolean = false
    private var searchItem: MenuItem? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        toolbar = view.findViewById(R.id.toolbar)
        recycler = view.findViewById(R.id.cardList)
        emptyState = view.findViewById(R.id.emptyState)
        noMatches = view.findViewById(R.id.noMatches)
        setupRecycler()
        setupToolbar()

        viewModel.allCards.observe(viewLifecycleOwner) { list ->
            toolbar.subtitle = getString(R.string.home_count_subtitle, list.size)
        }
        viewModel.cards.observe(viewLifecycleOwner) { list ->
            adapter.submitList(list)
            val total = viewModel.allCards.value.orEmpty().size
            val filtering = viewModel.searchQuery.value.orEmpty().isNotBlank()
            emptyState.visibility = if (total == 0) View.VISIBLE else View.GONE
            noMatches.visibility = if (total > 0 && filtering && list.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun setupRecycler() {
        adapter = CardTileAdapter(
            onTap = { display ->
                findNavController().navigate(
                    R.id.action_home_to_detail,
                    bundleOf("cardId" to display.id)
                )
            },
            onLongPress = { display -> showActionsSheet(display) },
            onDragHandleTouched = { vh -> itemTouchHelper?.startDrag(vh) }
        )
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter

        val callback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN,
            0
        ) {
            override fun isLongPressDragEnabled(): Boolean = false

            override fun onMove(
                rv: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
                val newList = adapter.currentList.toMutableList()
                val moved = newList.removeAt(from)
                newList.add(to, moved)
                adapter.submitList(newList)
                return true
            }

            override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
                super.clearView(rv, vh)
                viewModel.applyReorder(adapter.currentIds())
            }

            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) = Unit
        }
        itemTouchHelper = ItemTouchHelper(callback).also { it.attachToRecyclerView(recycler) }
    }

    private fun setupToolbar() {
        toolbar.inflateMenu(R.menu.menu_home)
        toolbar.setNavigationOnClickListener { onAddCardClicked() }
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_settings -> {
                    findNavController().navigate(R.id.action_home_to_settings); true
                }
                R.id.action_done_reorder -> {
                    exitReorderMode(); true
                }
                else -> false
            }
        }
        searchItem = toolbar.menu.findItem(R.id.action_search)
        val searchView = searchItem?.actionView as? SearchView
        searchView?.queryHint = getString(R.string.home_search_hint)
        searchView?.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextChange(newText: String?): Boolean {
                viewModel.searchQuery.value = newText.orEmpty()
                return true
            }
            override fun onQueryTextSubmit(query: String?): Boolean = false
        })
        searchItem?.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
            override fun onMenuItemActionExpand(item: MenuItem): Boolean = true
            override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                viewModel.searchQuery.value = ""
                return true
            }
        })
    }

    private fun onAddCardClicked() {
        if (reorderMode) return
        // Cap check reads the unfiltered total so an active search never masks the limit.
        val count = viewModel.allCards.value?.size ?: 0
        if (count >= MAX_CARDS) {
            Snackbar.make(requireView(), R.string.home_limit_reached, Snackbar.LENGTH_SHORT).show()
            return
        }
        findNavController().navigate(R.id.action_home_to_add)
    }

    private fun showActionsSheet(display: CardDisplay) {
        val sheet = BottomSheetDialog(requireContext())
        val content = layoutInflater.inflate(R.layout.dialog_card_actions, null)
        content.findViewById<android.widget.TextView>(R.id.sheetTitle).text = display.nickname
        content.findViewById<MaterialButton>(R.id.actionReorder).setOnClickListener {
            sheet.dismiss(); enterReorderMode()
        }
        content.findViewById<MaterialButton>(R.id.actionEdit).setOnClickListener {
            sheet.dismiss()
            findNavController().navigate(
                R.id.action_home_to_add,
                bundleOf("cardId" to display.id)
            )
        }
        content.findViewById<MaterialButton>(R.id.actionDelete).setOnClickListener {
            sheet.dismiss(); confirmDelete(display)
        }
        sheet.setContentView(content)
        sheet.show()
    }

    private fun confirmDelete(display: CardDisplay) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.detail_delete_confirm_title)
            .setMessage(R.string.detail_delete_confirm_body)
            .setPositiveButton(R.string.detail_delete) { _, _ -> viewModel.deleteById(display.id) }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun enterReorderMode() {
        // Reorder mutates the persisted sort order, so it only makes sense on the full
        // list. If the user has a live filter, block entry.
        if (viewModel.searchQuery.value.orEmpty().isNotBlank()) {
            Snackbar.make(requireView(), R.string.home_reorder_blocked_by_search, Snackbar.LENGTH_SHORT).show()
            return
        }
        reorderMode = true
        adapter.reorderMode = true
        toolbar.menu.findItem(R.id.action_settings).isVisible = false
        toolbar.menu.findItem(R.id.action_search).isVisible = false
        toolbar.menu.findItem(R.id.action_done_reorder).isVisible = true
        toolbar.navigationIcon = null
    }

    private fun exitReorderMode() {
        reorderMode = false
        adapter.reorderMode = false
        toolbar.menu.findItem(R.id.action_settings).isVisible = true
        toolbar.menu.findItem(R.id.action_search).isVisible = true
        toolbar.menu.findItem(R.id.action_done_reorder).isVisible = false
        toolbar.setNavigationIcon(R.drawable.ic_add)
    }

    companion object {
        private const val MAX_CARDS = 30
    }
}
