package com.pinekone.app

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.pinekone.app.databinding.ActivityFeedBinding
import com.pinekone.app.ui.FeedAdapter
import com.pinekone.app.ui.MeshViewModel
import kotlinx.coroutines.launch

class CustodyActivity : AppCompatActivity() {
    private lateinit var binding: ActivityFeedBinding
    private lateinit var viewModel: MeshViewModel
    private val adapter = FeedAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFeedBinding.inflate(layoutInflater)
        setContentView(binding.root)

        viewModel = ViewModelProvider(this, MeshViewModel.factory(application))[MeshViewModel::class.java]
        setSupportActionBar(binding.feedToolbar)
        supportActionBar?.title = getString(R.string.custody_title)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.feedToolbar.setNavigationOnClickListener { finish() }
        binding.feedEmpty.text = getString(R.string.custody_empty)
        binding.feedList.layoutManager = LinearLayoutManager(this)
        binding.feedList.adapter = adapter

        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                viewModel.custodyFeed.collect { rows ->
                    adapter.submitList(rows)
                    binding.feedEmpty.isVisible = rows.isEmpty()
                }
            }
        }
    }
}
