package com.nless.mypdf;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import java.util.List;

public class PdfListAdapter extends RecyclerView.Adapter<PdfListAdapter.ViewHolder> {

    private List<PdfItem> itemList;
    private OnItemInteractionListener listener;
    private boolean isGridView = false; // 控制当前是列表还是宫格

    public interface OnItemInteractionListener {
        void onClick(PdfItem item);
        void onLongClick(View anchorView, PdfItem item);
    }

    public PdfListAdapter(List<PdfItem> itemList, OnItemInteractionListener listener) {
        this.itemList = itemList;
        this.listener = listener;
    }

    public void setGridView(boolean isGridView) {
        this.isGridView = isGridView;
        notifyDataSetChanged(); // 刷新整个列表以改变视图
    }

    @Override
    public int getItemViewType(int position) {
        return isGridView ? 1 : 0;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        // 根据 viewType 加载不同的 XML 布局
        int layoutId = (viewType == 1) ? R.layout.item_file_grid : R.layout.item_file_list;
        View view = LayoutInflater.from(parent.getContext()).inflate(layoutId, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        PdfItem item = itemList.get(position);

        if (isGridView) {
            // 绑定宫格布局数据
            holder.tvGridName.setText(item.name);
            if (item.isFolder) {
                holder.tvGridTime.setVisibility(View.GONE);
            } else {
                holder.tvGridTime.setVisibility(View.VISIBLE);
                holder.tvGridTime.setText(item.time);
            }
        } else {
            // 绑定列表布局数据
            holder.tvFileName.setText(item.name);
            holder.tvFileTime.setText(item.time);

            // 处理路径隐藏（如果路径为空字符串，则隐藏地址框）
            if (item.path == null || item.path.isEmpty()) {
                holder.tvFilePath.setVisibility(View.GONE);
            } else {
                holder.tvFilePath.setVisibility(View.VISIBLE);
                holder.tvFilePath.setText(item.path);
            }

            if (item.isFolder) {
                holder.ivIcon.setImageResource(android.R.drawable.ic_menu_gallery);
                holder.tvFileTime.setVisibility(View.GONE);
            } else {
                holder.ivIcon.setImageResource(android.R.drawable.ic_menu_info_details);
                holder.tvFileTime.setVisibility(View.VISIBLE);
            }
        }

        // 绑定点击事件
        holder.itemView.setOnClickListener(v -> listener.onClick(item));
        holder.itemView.setOnLongClickListener(v -> {
            listener.onLongClick(v, item);
            return true;
        });
    }

    @Override
    public int getItemCount() {
        return itemList.size();
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        // 列表布局控件
        TextView tvFilePath, tvFileName, tvFileTime;
        ImageView ivIcon;
        // 宫格布局控件
        TextView tvGridName, tvGridTime;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            // 尝试获取两种布局的控件，不存在的会为 null
            tvFilePath = itemView.findViewById(R.id.tv_file_path);
            tvFileName = itemView.findViewById(R.id.tv_file_name);
            tvFileTime = itemView.findViewById(R.id.tv_file_time);
            ivIcon = itemView.findViewById(R.id.iv_file_icon);
            tvGridName = itemView.findViewById(R.id.tv_grid_file_name);
            tvGridTime = itemView.findViewById(R.id.tv_grid_file_time);
        }
    }
}